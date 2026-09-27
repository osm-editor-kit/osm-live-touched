import { defineConfig } from 'oxfmt'

export default defineConfig({
  printWidth: 100,
  semi: false,
  singleQuote: true,
  sortImports: {
    newlinesBetween: false,
  },
  sortPackageJson: {
    sortScripts: true,
  },
  ignorePatterns: [
    '**/node_modules/**',
    '**/dist/**',
    '**/out/**',
    '**/build/**',
    '**/coverage/**',
    '**/generated/**',
    '**/*.md',
  ],
})
