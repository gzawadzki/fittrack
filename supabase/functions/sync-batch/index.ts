// M5: POST /sync-batch
//
// Contract (IMPLEMENTATION_PLAN.md section 4):
//   input:  { batchId, cursor, operations[] }
//   output: { ackedOperationIds[], changes[], nextCursor }
//
// Request body:
// {
//   "batchId": "uuid",
//   "cursor": 0,                     // last nextCursor the client has seen (0 for first sync)
//   "operations": [
//     {
//       "operationId": "uuid",       // client-generated, globally unique, used for idempotency
//       "entityType": "workout_sessions",
//       "entityId": "uuid",
//       "op": "CREATE" | "UPDATE" | "DELETE",
//       "baseVersion": 3 | null,     // required for UPDATE/DELETE, must be null for CREATE
//       "data": { ... }              // whitelisted columns only, see _shared/entities.ts
//     }
//   ]
// }
//
// Response body:
// {
//   "ackedOperationIds": ["uuid", ...],
//   "changes": [
//     { "entityType": "workout_sessions", "id": "uuid", "version": 4,
//       "updatedAt": "...", "deletedAt": null, "data": { ...full row... } }
//   ],
//   "nextCursor": 128
// }
//
// Idempotency / dedup strategy (two layers):
//   1. Batch-level cache (sync_batches): retrying the exact same batchId for
//      the same user short-circuits to the stored response with zero writes.
//      This is what guarantees a byte-identical result on retry.
//   2. Operation-level ledger (sync_operations): even if a client reuses an
//      operationId under a *different* batchId (e.g. it retried with a new
//      batchId after a timeout), that operation is recognized as already
//      applied and is acked again without being re-applied.
//
// Conflict resolution: UPDATE/DELETE operations must carry baseVersion. The
// operation is only applied if baseVersion equals the row's current
// version (single-device optimistic concurrency, per section 5). Mismatch
// -> the whole batch is rejected with VERSION_CONFLICT so the client can
// re-pull (via `changes`) and resolve before retrying.
//
// NOTE ON ATOMICITY: operations are validated in a first pass, then applied
// in a second pass, both within one function invocation, but this is not a
// single Postgres transaction across all statements (PostgREST/supabase-js
// does not expose multi-statement transactions to Edge Functions). For a
// single low-concurrency user this is an acceptable MVP tradeoff; the batch
// response cache still means a *retry* can never duplicate data. See
// supabase/HANDOFF.md "Known limitations" for a note on hardening this with
// a single plpgsql RPC if cross-row atomicity is ever required.

import { getServiceClient } from "../_shared/supabaseAdmin.ts";
import { requireUser } from "../_shared/auth.ts";
import {
  errorResponse,
  internalError,
  jsonResponse,
  methodNotAllowed,
  validationFailed,
  versionConflict,
} from "../_shared/errors.ts";
import { handleCorsPreflight } from "../_shared/cors.ts";
import { ALL_PULLABLE_TABLES, ENTITY_TABLES, isWritableEntityType } from "../_shared/entities.ts";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_OPERATIONS_PER_BATCH = 500;
const MAX_CHANGES_PER_PULL = 500;

type OpKind = "CREATE" | "UPDATE" | "DELETE";

interface RawOperation {
  operationId: string;
  entityType: string;
  entityId: string;
  op: OpKind;
  baseVersion: number | null;
  data: Record<string, unknown>;
}

interface RawBody {
  batchId: string;
  cursor: number;
  operations: RawOperation[];
}

function isUuid(v: unknown): v is string {
  return typeof v === "string" && UUID_RE.test(v);
}

function validateBody(body: unknown): { ok: true; value: RawBody } | { ok: false; message: string } {
  if (typeof body !== "object" || body === null) {
    return { ok: false, message: "Request body must be a JSON object" };
  }
  const b = body as Record<string, unknown>;
  if (!isUuid(b.batchId)) return { ok: false, message: "batchId must be a uuid" };
  if (typeof b.cursor !== "number" || !Number.isFinite(b.cursor) || b.cursor < 0) {
    return { ok: false, message: "cursor must be a non-negative number" };
  }
  if (!Array.isArray(b.operations)) return { ok: false, message: "operations must be an array" };
  if (b.operations.length > MAX_OPERATIONS_PER_BATCH) {
    return { ok: false, message: `operations exceeds max batch size of ${MAX_OPERATIONS_PER_BATCH}` };
  }
  const operations: RawOperation[] = [];
  for (const [i, raw] of b.operations.entries()) {
    if (typeof raw !== "object" || raw === null) {
      return { ok: false, message: `operations[${i}] must be an object` };
    }
    const o = raw as Record<string, unknown>;
    if (!isUuid(o.operationId)) return { ok: false, message: `operations[${i}].operationId must be a uuid` };
    if (typeof o.entityType !== "string") return { ok: false, message: `operations[${i}].entityType must be a string` };
    if (!isUuid(o.entityId)) return { ok: false, message: `operations[${i}].entityId must be a uuid` };
    if (o.op !== "CREATE" && o.op !== "UPDATE" && o.op !== "DELETE") {
      return { ok: false, message: `operations[${i}].op must be CREATE, UPDATE, or DELETE` };
    }
    if (o.op === "CREATE") {
      if (o.baseVersion !== null && o.baseVersion !== undefined) {
        return { ok: false, message: `operations[${i}].baseVersion must be null for CREATE` };
      }
    } else {
      if (typeof o.baseVersion !== "number" || o.baseVersion < 1) {
        return { ok: false, message: `operations[${i}].baseVersion must be a positive integer for UPDATE/DELETE` };
      }
    }
    const data = (o.data ?? {}) as Record<string, unknown>;
    if (typeof data !== "object" || data === null || Array.isArray(data)) {
      return { ok: false, message: `operations[${i}].data must be an object` };
    }
    if (!isWritableEntityType(o.entityType)) {
      return { ok: false, message: `operations[${i}].entityType '${o.entityType}' is not writable via sync-batch` };
    }
    const def = ENTITY_TABLES[o.entityType];
    for (const key of Object.keys(data)) {
      if (!def.columns.includes(key)) {
        return { ok: false, message: `operations[${i}].data has unknown/forbidden field '${key}' for ${o.entityType}` };
      }
    }
    operations.push({
      operationId: o.operationId as string,
      entityType: o.entityType,
      entityId: o.entityId as string,
      op: o.op as OpKind,
      baseVersion: (o.baseVersion ?? null) as number | null,
      data,
    });
  }
  return { ok: true, value: { batchId: b.batchId as string, cursor: b.cursor as number, operations } };
}

Deno.serve(async (req: Request) => {
  const preflight = handleCorsPreflight(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return methodNotAllowed();

  const admin = getServiceClient();

  const auth = await requireUser(req, admin);
  if (!auth.ok) return auth.response;
  const userId = auth.user.id;

  let json: unknown;
  try {
    json = await req.json();
  } catch {
    return validationFailed("Request body must be valid JSON");
  }

  const validated = validateBody(json);
  if (!validated.ok) return validationFailed(validated.message);
  const { batchId, cursor, operations } = validated.value;

  try {
    // Layer 1: exact batch retry -> return the cached response verbatim.
    const { data: cachedBatch, error: cachedBatchError } = await admin
      .from("sync_batches")
      .select("response")
      .eq("batch_id", batchId)
      .eq("user_id", userId)
      .maybeSingle();
    if (cachedBatchError) throw cachedBatchError;
    if (cachedBatch) {
      return jsonResponse(cachedBatch.response);
    }

    // Layer 2: per-operation idempotency ledger.
    const opIds = operations.map((o) => o.operationId);
    const alreadyApplied = new Set<string>();
    if (opIds.length > 0) {
      const { data: existingOps, error: existingOpsError } = await admin
        .from("sync_operations")
        .select("operation_id")
        .eq("user_id", userId)
        .in("operation_id", opIds);
      if (existingOpsError) throw existingOpsError;
      for (const row of existingOps ?? []) alreadyApplied.add(row.operation_id as string);
    }

    const toApply = operations.filter((o) => !alreadyApplied.has(o.operationId));

    // Phase 1: validate ownership + version of every not-yet-applied op
    // before mutating anything.
    for (const op of toApply) {
      const table = ENTITY_TABLES[op.entityType].table;
      if (op.op === "CREATE") {
        const { data: existing, error } = await admin
          .from(table)
          .select("id,user_id")
          .eq("id", op.entityId)
          .maybeSingle();
        if (error) throw error;
        if (existing && existing.user_id !== userId) {
          return errorResponse(403, {
            code: "FORBIDDEN",
            message: `entity ${op.entityId} already exists and is owned by another user`,
            retryable: false,
            retryAfterSeconds: null,
          });
        }
        // If it already exists for this same user (e.g. a half-applied
        // earlier attempt whose ledger write failed), we'll upsert in
        // phase 2 rather than erroring here.
      } else {
        const { data: existing, error } = await admin
          .from(table)
          .select("id,user_id,version")
          .eq("id", op.entityId)
          .maybeSingle();
        if (error) throw error;
        if (!existing || existing.user_id !== userId) {
          return errorResponse(404, {
            code: "NOT_FOUND",
            message: `operation ${op.operationId}: ${op.entityType} ${op.entityId} not found`,
            retryable: false,
            retryAfterSeconds: null,
          });
        }
        if (existing.version !== op.baseVersion) {
          return versionConflict(
            `operation ${op.operationId}: baseVersion ${op.baseVersion} does not match current version ${existing.version} for ${op.entityType} ${op.entityId}; pull latest changes and retry`,
          );
        }
      }

      // The database also has a partial unique index. This preflight turns
      // its expected cross-device collision into the normal conflict/pull
      // protocol instead of an opaque 500. Excluding this entity allows an
      // UPDATE of the already-active row itself.
      if (
        op.entityType === "workout_sessions" &&
        op.op !== "DELETE" &&
        op.data.status === "ACTIVE"
      ) {
        const { data: otherActive, error: activeError } = await admin
          .from("workout_sessions")
          .select("id")
          .eq("user_id", userId)
          .eq("status", "ACTIVE")
          .is("deleted_at", null)
          .neq("id", op.entityId)
          .limit(1)
          .maybeSingle();
        if (activeError) throw activeError;
        if (otherActive) {
          return versionConflict(
            `operation ${op.operationId}: another ACTIVE workout must be pulled and reconciled first`,
          );
        }
      }
    }

    // Phase 2: apply.
    const appliedLedgerRows: {
      operation_id: string;
      user_id: string;
      batch_id: string;
      entity_type: string;
      entity_id: string;
      applied_version: number;
    }[] = [];

    for (const op of toApply) {
      const table = ENTITY_TABLES[op.entityType].table;
      let resultVersion: number;

      if (op.op === "CREATE") {
        const row = { id: op.entityId, user_id: userId, ...op.data };
        const { data: upserted, error } = await admin
          .from(table)
          .upsert(row, { onConflict: "id", ignoreDuplicates: false })
          .select("version")
          .single();
        if (error) {
          if (op.entityType === "workout_sessions" && error.code === "23505") {
            return versionConflict(
              `operation ${op.operationId}: another ACTIVE workout was created concurrently`,
            );
          }
          throw error;
        }
        resultVersion = upserted.version as number;
      } else if (op.op === "UPDATE") {
        const { data: updated, error } = await admin
          .from(table)
          .update(op.data)
          .eq("id", op.entityId)
          .eq("user_id", userId)
          .eq("version", op.baseVersion)
          .select("version")
          .maybeSingle();
        if (error) {
          if (op.entityType === "workout_sessions" && error.code === "23505") {
            return versionConflict(
              `operation ${op.operationId}: another ACTIVE workout was activated concurrently`,
            );
          }
          throw error;
        }
        if (!updated) {
          return versionConflict(
            `operation ${op.operationId}: ${op.entityType} ${op.entityId} changed concurrently; pull latest changes and retry`,
          );
        }
        resultVersion = updated.version as number;
      } else {
        const { data: deleted, error } = await admin
          .from(table)
          .update({ deleted_at: new Date().toISOString() })
          .eq("id", op.entityId)
          .eq("user_id", userId)
          .eq("version", op.baseVersion)
          .select("version")
          .maybeSingle();
        if (error) throw error;
        if (!deleted) {
          return versionConflict(
            `operation ${op.operationId}: ${op.entityType} ${op.entityId} changed concurrently; pull latest changes and retry`,
          );
        }
        resultVersion = deleted.version as number;
      }

      appliedLedgerRows.push({
        operation_id: op.operationId,
        user_id: userId,
        batch_id: batchId,
        entity_type: op.entityType,
        entity_id: op.entityId,
        applied_version: resultVersion,
      });
    }

    if (appliedLedgerRows.length > 0) {
      const { error: ledgerError } = await admin.from("sync_operations").insert(appliedLedgerRows);
      if (ledgerError) throw ledgerError;
    }

    // Build the pull side: everything changed since `cursor`, across all
    // syncable tables (including the read-only coach_suggestions), merged
    // and ordered by the global sync_seq cursor.
    type ChangeRow = {
      entityType: string;
      id: string;
      version: number;
      updatedAt: string;
      deletedAt: string | null;
      data: Record<string, unknown>;
      syncSeq: number;
    };
    const changeRows: ChangeRow[] = [];

    for (const [entityType, def] of Object.entries(ALL_PULLABLE_TABLES)) {
      const { data: rows, error } = await admin
        .from(def.table)
        .select("*")
        .eq("user_id", userId)
        .gt("sync_seq", cursor)
        .order("sync_seq", { ascending: true })
        .limit(MAX_CHANGES_PER_PULL);
      if (error) throw error;
      for (const row of rows ?? []) {
        const { sync_seq, user_id: _uid, ...data } = row as Record<string, unknown>;
        changeRows.push({
          entityType,
          id: row.id as string,
          version: row.version as number,
          updatedAt: row.updated_at as string,
          deletedAt: (row.deleted_at as string | null) ?? null,
          data,
          syncSeq: sync_seq as number,
        });
      }
    }

    changeRows.sort((a, b) => a.syncSeq - b.syncSeq);
    const limited = changeRows.slice(0, MAX_CHANGES_PER_PULL);
    const nextCursor = limited.length > 0 ? limited[limited.length - 1].syncSeq : cursor;

    const ackedOperationIds = [...alreadyApplied, ...toApply.map((o) => o.operationId)];

    const responseBody = {
      ackedOperationIds,
      changes: limited.map(({ syncSeq: _s, ...rest }) => rest),
      nextCursor,
    };

    const { error: cacheError } = await admin
      .from("sync_batches")
      .insert({ batch_id: batchId, user_id: userId, response: responseBody })
      .select()
      .maybeSingle();
    // A conflict here means a concurrent identical request already cached a
    // response; that's fine, ours is equivalent and we still return it.
    if (cacheError && cacheError.code !== "23505") throw cacheError;

    return jsonResponse(responseBody);
  } catch (err) {
    console.error("sync-batch failed", err instanceof Error ? err.message : String(err));
    return internalError();
  }
});
