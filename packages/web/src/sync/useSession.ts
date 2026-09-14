import { useCallback, useEffect, useRef, useState } from 'react'
import type { BoardElement } from '@meshboard/shared-protocol'
import { BoardDocument } from './BoardDocument'
import { INITIAL_STATUS, MeshSession } from './MeshSession'

const ROOM_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function readRoom() {
  const value = new URLSearchParams(location.hash.slice(1)).get('room')
  return value && ROOM_ID.test(value) ? value : null
}

export function useSession(document: BoardDocument) {
  const [room, setRoom] = useState(readRoom)
  const [revision, setRevision] = useState(0)
  const [status, setStatus] = useState(INITIAL_STATUS)
  const [drafts, setDrafts] = useState<Record<string, BoardElement>>({})
  const [error, setError] = useState<string | null>(() => location.hash && !readRoom() ? 'This invite link is not valid. You can start a new shared board.' : null)
  const session = useRef<MeshSession | null>(null)

  useEffect(() => document.onError(setError), [document])
  useEffect(() => {
    if (!room) return
    setStatus(INITIAL_STATUS)
    setDrafts({})
    const current = new MeshSession(room, document, setStatus, (id, element) => {
      setDrafts(previous => {
        const next = { ...previous }
        if (element) next[id] = element
        else delete next[id]
        return next
      })
    })
    session.current = current
    void current.start()
    return () => { current.dispose(); session.current = null }
  }, [room, revision, document])

  useEffect(() => {
    // Navigating to another invite creates a new document, never merges rooms.
    const onHashChange = () => { if (readRoom() !== room) location.reload() }
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [room])

  const preview = useCallback((element: BoardElement | null) => session.current?.preview(element), [])
  function share() {
    if (room) return
    const id = crypto.randomUUID()
    const url = new URL(location.href)
    url.hash = new URLSearchParams({ room: id }).toString()
    history.replaceState(null, '', url)
    setError(null)
    setRoom(id)
  }
  function leave() {
    session.current?.dispose()
    const url = new URL(location.href)
    url.hash = ''
    location.replace(url)
  }
  const invite = room ? new URL(`/#${new URLSearchParams({ room })}`, location.origin).href : ''
  return { room, invite, status, drafts: Object.values(drafts), preview, share, leave, error: error ?? status.error,
    retry: () => { setError(null); setRevision(value => value + 1) }, dismissError: () => setError(null) }
}
