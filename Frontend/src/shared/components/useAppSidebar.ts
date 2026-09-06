import { useState } from 'react'

export const SIDEBAR_STORAGE_KEY = 'logicdraft-sidebar-collapsed'

export function useSidebarCollapsed() {
  return useState(() => localStorage.getItem(SIDEBAR_STORAGE_KEY) === 'true')
}
