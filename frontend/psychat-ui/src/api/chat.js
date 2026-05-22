import request from './request'

export function sendMessage(message, sessionId = 'default') {
  const token = localStorage.getItem('token')
  // SSE needs direct fetch, not axios
  return fetch('/api/chat/send', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    body: JSON.stringify({ message, sessionId }),
  })
}

export function getHistory(sessionId = 'default') {
  return request.get('/chat/history', { params: { sessionId } })
}
