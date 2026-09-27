import { defineConfig } from 'oxlint'

export default defineConfig({
  plugins: ['typescript', 'import'],
  categories: {
    correctness: 'error',
    suspicious: 'error',
  },
  env: {
    es2024: true,
    node: true,
  },
  rules: {
    'typescript/switch-exhaustiveness-check': 'error',
    'eslint/no-underscore-dangle': 'off',
    'import/no-unassigned-import': 'off',
  },
  overrides: [
    {
      files: ['packages/live-touched/**/*.ts'],
      env: {
        browser: true,
      },
    },
    {
      files: ['**/*.{test,spec}.{ts,tsx}'],
      env: {
        vitest: true,
      },
    },
  ],
  ignorePatterns: [
    '**/node_modules/**',
    '**/dist/**',
    '**/out/**',
    '**/build/**',
    '**/coverage/**',
    '**/generated/**',
  ],
})
