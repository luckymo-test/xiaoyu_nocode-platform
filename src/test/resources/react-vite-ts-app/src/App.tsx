import { useState } from 'react'

export default function App() {
  const [count, setCount] = useState(0)

  return (
    <main className="probe-shell">
      <p className="eyebrow">React + TypeScript</p>
      <h1>Sandbox build probe</h1>
      <p>This page verifies that the StackProfile build and preview path is real.</p>
      <button type="button" onClick={() => setCount((value) => value + 1)}>
        Interaction count: {count}
      </button>
    </main>
  )
}
