import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import './styles.css'
import './designerCanvas.css'
import './draftAutosave.css'

const app = createApp(App)
app.config.errorHandler = (error, _instance, info) => {
  console.error('Unhandled Vue runtime error', { info, name: error instanceof Error ? error.name : typeof error })
}
app.use(router).mount('#app')
