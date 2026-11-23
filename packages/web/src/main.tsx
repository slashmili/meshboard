import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { browserProblem } from './browserSupport'
import './styles.css'

const root = createRoot(document.getElementById('root')!)
function showProblem(message: string) {
  root.render(<main className="browser-problem" role="alert">
    <h1>Meshboard could not open</h1>
    <p>{message}</p>
    <p>Use Chrome or Edge 113+, Firefox 114+, or Safari 17+.</p>
  </main>)
}
const problem = browserProblem()
if (problem) {
  showProblem(problem)
} else {
  // Do not initialize documents or join an invitation in an unsupported browser.
  void import('./App').then(({ App }) => {
    root.render(<StrictMode><App /></StrictMode>)
  }).catch(() => showProblem('Reload the page. If the problem continues, update your browser or try another supported browser.'))
}
