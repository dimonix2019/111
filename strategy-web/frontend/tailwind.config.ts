import type { Config } from 'tailwindcss'

export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        surface: {
          page: '#050a14',
          card: '#0d1425',
          inner: '#111c32',
          inset: '#080f1a',
          active: '#162d45',
          border: 'rgba(148, 163, 184, 0.14)',
          'border-soft': 'rgba(148, 163, 184, 0.08)',
        },
        panel: {
          DEFAULT: '#0d1425',
          border: 'rgba(148, 163, 184, 0.14)',
          'border-soft': 'rgba(148, 163, 184, 0.08)',
        },
        ink: {
          1: 'rgba(241, 245, 249, 0.94)',
          2: 'rgba(203, 213, 225, 0.82)',
          3: 'rgba(148, 163, 184, 0.58)',
        },
        accent: {
          DEFAULT: '#3b82f6',
          muted: 'rgba(59, 130, 246, 0.35)',
        },
        good: '#10b981',
        warn: '#d4b86a',
        bad: '#f43f5e',
      },
      boxShadow: {
        panel: '0 4px 24px rgba(0, 0, 0, 0.38), inset 0 1px 0 rgba(255, 255, 255, 0.04)',
        card: '0 8px 32px rgba(0, 0, 0, 0.28)',
        inset: 'inset 0 1px 2px rgba(0, 0, 0, 0.35)',
      },
      borderRadius: {
        xl2: '16px',
        pill: '9999px',
      },
    },
  },
  plugins: [],
} satisfies Config
