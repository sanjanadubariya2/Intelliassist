"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, api, tokenStore, type CurrentUser } from "./api";

interface AuthState {
  user: CurrentUser | null;
  /** True until the stored token has been checked, so pages can hold render. */
  loading: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (input: {
    name: string;
    email: string;
    password: string;
    organizationName: string;
  }) => Promise<void>;
  /** Completes a single sign-on: stores the token the backend issued. */
  signInWithToken: (token: string) => Promise<void>;
  signOut: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  /**
   * A stored token is not proof of a session — it may be expired or revoked.
   * The only way to know is to ask, so every load starts with `/auth/me`.
   */
  useEffect(() => {
    if (!tokenStore.get()) {
      setLoading(false);
      return;
    }

    let cancelled = false;
    api.auth
      .me()
      .then((me) => {
        if (!cancelled) setUser(me);
      })
      .catch((error) => {
        // An expired token is the ordinary case, not an incident.
        if (error instanceof ApiError && error.isUnauthorized) tokenStore.clear();
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(async (email: string, password: string) => {
    const { token } = await api.auth.login({ email, password });
    tokenStore.set(token);
    setUser(await api.auth.me());
  }, []);

  const signUp = useCallback(
    async (input: { name: string; email: string; password: string; organizationName: string }) => {
      const { token } = await api.auth.register(input);
      tokenStore.set(token);
      setUser(await api.auth.me());
    },
    [],
  );

  const signInWithToken = useCallback(async (token: string) => {
    tokenStore.set(token);
    try {
      setUser(await api.auth.me());
    } catch (error) {
      // A token that does not work is not a session.
      tokenStore.clear();
      throw error;
    }
  }, []);

  const signOut = useCallback(() => {
    tokenStore.clear();
    setUser(null);
    router.push("/login");
  }, [router]);

  return (
    <AuthContext.Provider value={{ user, loading, signIn, signUp, signInWithToken, signOut }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside an AuthProvider");
  return context;
}
