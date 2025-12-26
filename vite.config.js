import { defineConfig } from 'vite';

export default defineConfig({
  root: 'src',
  base: './',  // Use relative paths for extension compatibility
  build: {
    outDir: '../dist-vite',
    emptyOutDir: true,
    // For browser extension: inline everything, no code splitting
    rollupOptions: {
      input: {
        popup: 'src/popup.html'
      },
      output: {
        entryFileNames: '[name].js',
        inlineDynamicImports: true
      }
    }
  },
  // Disable CSS code splitting for extension compatibility
  css: {
    modules: {
      localsConvention: 'camelCase'
    }
  }
});
