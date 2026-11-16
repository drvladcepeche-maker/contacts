export type WorkerRunBudget = {
  readonly candidatesProcessed: number;
  readonly telegramRequestsMade: number;
  canProcessCandidate(): boolean;
  recordCandidate(): void;
  canMakeTelegramRequest(): boolean;
  recordTelegramRequest(): void;
};

export function createWorkerRunBudget(
  maxCandidates: number,
  maxTelegramRequests: number,
): WorkerRunBudget {
  const candidateLimit = Math.max(1, Math.floor(maxCandidates));
  const requestLimit = Math.max(1, Math.floor(maxTelegramRequests));
  let candidatesProcessed = 0;
  let telegramRequestsMade = 0;

  return {
    get candidatesProcessed() { return candidatesProcessed; },
    get telegramRequestsMade() { return telegramRequestsMade; },
    canProcessCandidate: () => candidatesProcessed < candidateLimit,
    recordCandidate: () => { candidatesProcessed += 1; },
    canMakeTelegramRequest: () => telegramRequestsMade < requestLimit,
    recordTelegramRequest: () => { telegramRequestsMade += 1; },
  };
}
