// Common error JSON shape frozen in IMPLEMENTATION_PLAN.md section 4.
//
// {
//   "error": {
//     "code": "STABLE_MACHINE_CODE",
//     "message": "Safe message for the user",
//     "retryable": false,
//     "retryAfterSeconds": null
//   }
// }

export interface FitbitErrorBody {
  error: {
    code: string;
    message: string;
    retryable: boolean;
    retryAfterSeconds: number | null;
  };
}

export class FitbitFunctionError extends Error {
  code: string;
  httpStatus: number;
  retryable: boolean;
  retryAfterSeconds: number | null;

  constructor(opts: {
    code: string;
    message: string;
    httpStatus: number;
    retryable?: boolean;
    retryAfterSeconds?: number | null;
  }) {
    super(opts.message);
    this.code = opts.code;
    this.httpStatus = opts.httpStatus;
    this.retryable = opts.retryable ?? false;
    this.retryAfterSeconds = opts.retryAfterSeconds ?? null;
  }

  toBody(): FitbitErrorBody {
    return {
      error: {
        code: this.code,
        message: this.message,
        retryable: this.retryable,
        retryAfterSeconds: this.retryAfterSeconds,
      },
    };
  }
}

export function errorResponse(err: FitbitFunctionError): Response {
  return new Response(JSON.stringify(err.toBody()), {
    status: err.httpStatus,
    headers: { "content-type": "application/json" },
  });
}

export const ERR = {
  UNAUTHORIZED: "FITBIT_UNAUTHORIZED",
  NOT_CONNECTED: "FITBIT_NOT_CONNECTED",
  INVALID_STATE: "FITBIT_INVALID_OAUTH_STATE",
  RATE_LIMITED: "FITBIT_RATE_LIMITED",
  UPSTREAM_ERROR: "FITBIT_UPSTREAM_ERROR",
  BAD_REQUEST: "FITBIT_BAD_REQUEST",
  TOKEN_EXCHANGE_FAILED: "FITBIT_TOKEN_EXCHANGE_FAILED",
} as const;
