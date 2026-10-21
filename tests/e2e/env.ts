type ProcLike = { env?: Record<string, string | undefined> };

function proc(): ProcLike | undefined {
  return (globalThis as { process?: ProcLike }).process;
}

export function readEnv(name: string): string | undefined {
  const value = proc()?.env?.[name];
  return value === undefined || value === '' ? undefined : value;
}

/**
 * Strict mode turns the informational assertions (title screen reached, EPK
 * served, no game runtime exceptions) into hard failures. It is off by default
 * because the TeaVM port of MC 26.1.2 is still in progress.
 */
export function isStrictGame(): boolean {
  const value = readEnv('E2E_STRICT_GAME');
  return value === '1' || value === 'true';
}
