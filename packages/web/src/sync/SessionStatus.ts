export type SessionStatus = {
  signaling: boolean; connected: number; connecting: number; relay: number;
  error: string | null; localDevelopment: boolean;
}
export const INITIAL_STATUS: SessionStatus = { signaling: false, connected: 0, connecting: 0, relay: 0, error: null, localDevelopment: true }
