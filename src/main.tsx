import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './assets/main.css'
import App from './App'
import { applyTheme, getTheme } from './theme'

applyTheme(getTheme()) // before the first paint, so there is no flash of the wrong colours

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>
)
