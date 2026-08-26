import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  test: {
    // jsdom, а не node: тесты интерсептора работают с localStorage и navigator, а тесты
    // форм — с настоящим DOM. Отдельного vitest.config.js нет намеренно: конфиг сборки и
    // конфиг тестов должны разъезжаться как можно меньше (алиасы, плагины, env — одни и те же).
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
    // Часовой пояс фиксирован: половина хелперов (datetimeLocal, taskDisplay) переводит
    // instant в локальное время, и на машине разработчика и в CI это должно быть одно и то же.
    // Сами тесты написаны так, чтобы не зависеть от зоны, но подстраховаться дешевле, чем
    // разбираться потом, почему билд красный только в CI.
    env: { TZ: 'UTC' },
  },
})
