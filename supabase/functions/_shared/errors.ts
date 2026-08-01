// M5 shared helper: the common error JSON shape frozen in
// IMPLEMENTATION_PLAN.md section 4:
//
//   { "error": { "code": "STABLE_MACHINE_CODE", "message": "...",
//                "retryable": bool, "retryAfterSeconds": number|null } }
//
// Every Edge Function owned by this module (sync-batch, suggestion-decision)
// must return errors through `errorResponse` / the named helpers below so
// the shape and headers stay consistent, and so a service_role key or stack
// trace can never leak into a response body.

import { CORS_HEADERS } from "./cors.ts";

export type ErrorCode =
  | "UNAUTHENTICATED"
  | "FORBIDDEN"
  | "NOT_FOUND"
  | "VALIDATION_FAILED"
  | "VERSION_CONFLICT"
  | "ALREADY_DECIDED"
  | "METHOD_NOT_ALLOWED"
  | "RATE_LIMITED"
  | "INTERNAL_ERROR";

export interface ApiError {
  code: ErrorCode;
  message: string;
  retryable: boolean;
  retryAfterSeconds: number | null;
}

export function errorResponse(status: number, err: ApiError): Response {
  return new Response(JSON.stringify({ error: err }), {
    status,
    headers: { ...CORS_HEADERS, "content-type": "application/json" },
  });
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS_HEADERS, "content-type": "application/json" },
  });
}

export const unauthenticated = (message = "Missing or invalid Supabase JWT") =>
  errorResponse(401, { code: "UNAUTHENTICATED", message, retryable: false, retryAfterSeconds: null });

export const forbidden = (message = "Not allowed") =>
  errorResponse(403, { code: "FORBIDDEN", message, retryable: false, retryAfterSeconds: null });

export const notFound = (message = "Resource not found") =>
  errorResponse(404, { code: "NOT_FOUND", message, retryable: false, retryAfterSeconds: null });

export const validationFailed = (message: string) =>
  errorResponse(400, { code: "VALIDATION_FAILED", message, retryable: false, retryAfterSeconds: null });

export const versionConflict = (message: string) =>
  errorResponse(409, { code: "VERSION_CONFLICT", message, retryable: true, retryAfterSeconds: null });

export const alreadyDecided = (message: string) =>
  errorResponse(409, { code: "ALREADY_DECIDED", message, retryable: false, retryAfterSeconds: null });

export const methodNotAllowed = (message = "Only POST is supported") =>
  errorResponse(405, { code: "METHOD_NOT_ALLOWED", message, retryable: false, retryAfterSeconds: null });

export const internalError = (message = "Unexpected server error") =>
  errorResponse(500, { code: "INTERNAL_ERROR", message, retryable: true, retryAfterSeconds: 5 });
