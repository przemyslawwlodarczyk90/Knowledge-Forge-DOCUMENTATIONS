import axios from 'axios'
import { getClientId } from '@/ws/clientId'

const api = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

// Każde żądanie niesie clientId tej karty przeglądarki — backend wstawia go do zdarzenia
// WebSocket jako sourceClientId, żeby karta, która sama wykonała operację, mogła rozpoznać
// i zignorować (albo potraktować jako samo potwierdzenie) własne zdarzenie zwrotne.
api.interceptors.request.use((config) => {
  config.headers['X-Client-Id'] = getClientId()
  return config
})

// Obsługa odpowiedzi — aplikacja działa bez logowania, brak obsługi tokenu/401
api.interceptors.response.use(
  (res) => res,
  (err) => {
    const status = err.response?.status

    // Normalizuj błąd — wyciągnij message z backendu
    const message =
      err.response?.data?.message ??
      err.response?.data?.error ??
      err.message ??
      'Nieznany błąd'

    return Promise.reject({ ...err, message, status })
  }
)

export default api