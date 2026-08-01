// Common error JSON shape (IMPLEMENTATION_PLAN.md section 4) + small response helpers.

import { apiError } from "./types.ts";

export function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function errorResponse(
  status: number,
  code: string,
  message: string,
  opts: { retryable?: boolean; retryAfterSeconds?: number | null } = {},
): Response {
  return jsonResponse(apiError(code, message, opts), status);
}

export const ErrorCodes = {
  UNAUTHENTICATED: "UNAUTHENTICATED",
  INVALID_REQUEST: "INVALID_REQUEST",
  UPSTREAM_MODEL_ERROR: "UPSTREAM_MODEL_ERROR",
  UPSTREAM_MODEL_INVALID_OUTPUT: "UPSTREAM_MODEL_INVALID_OUTPUT",
  INTERNAL_ERROR: "INTERNAL_ERROR",
} as const;
