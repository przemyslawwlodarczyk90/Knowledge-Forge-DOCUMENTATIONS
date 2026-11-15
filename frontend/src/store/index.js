import { create } from 'zustand'

// ── UI Store ──────────────────────────────────────────────────────
let toastId = 0

export const useUIStore = create((set, get) => ({
  toasts:      [],
  sidebarOpen: typeof window !== 'undefined' ? window.innerWidth > 900 : true,

  addToast: (message, type = 'info', duration = 4000) => {
    const id = ++toastId
    set((state) => ({
      toasts: [...state.toasts, { id, message, type, duration }],
    }))
    if (duration > 0) setTimeout(() => get().removeToast(id), duration)
    return id
  },

  removeToast: (id) =>
    set((state) => ({
      toasts: state.toasts.filter((t) => t.id !== id),
    })),

  toggleSidebar:  () => set((state) => ({ sidebarOpen: !state.sidebarOpen })),
  setSidebarOpen: (v) => set({ sidebarOpen: v }),
}))

export const toast = {
  success: (msg, d) => useUIStore.getState().addToast(msg, 'success', d),
  error:   (msg, d) => useUIStore.getState().addToast(msg, 'error',   d),
  info:    (msg, d) => useUIStore.getState().addToast(msg, 'info',    d),
  warn:    (msg, d) => useUIStore.getState().addToast(msg, 'warn',    d),
}