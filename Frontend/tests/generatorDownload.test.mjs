import assert from 'node:assert/strict'
import test from 'node:test'

import {
  fallbackFilename,
  filenameFromDisposition,
  triggerBlobDownload,
} from '../src/features/diagram/services/generatorDownload.ts'

test('usa Content-Disposition y soporta filename UTF-8', () => {
  assert.equal(filenameFromDisposition('attachment; filename="barbero.zip"', 'fallback.zip'), 'barbero.zip')
  assert.equal(filenameFromDisposition("attachment; filename*=UTF-8''mi%20aplicacion.zip", 'fallback.zip'), 'mi aplicacion.zip')
})

test('genera fallbacks técnicos para backend y fullstack', () => {
  assert.equal(fallbackFilename('Gestión Barbería', 'backend'), 'gestion-barberia-backend.zip')
  assert.equal(fallbackFilename('Gestión Barbería', 'fullstack'), 'gestion-barberia.zip')
  assert.equal(filenameFromDisposition(undefined, 'gestion-barberia.zip'), 'gestion-barberia.zip')
  assert.equal(filenameFromDisposition('attachment; filename="../inseguro.zip"', 'seguro.zip'), 'seguro.zip')
})

test('dispara la descarga y revoca el objeto URL', () => {
  const events = []
  const anchor = {
    href: '', download: '',
    click: () => events.push('click'),
    remove: () => events.push('remove'),
  }
  const platform = {
    createObjectUrl: () => { events.push('create'); return 'blob:logicdraft' },
    revokeObjectUrl: (url) => events.push(`revoke:${url}`),
    createAnchor: () => anchor,
    appendAnchor: () => events.push('append'),
    defer: (callback) => callback(),
  }

  triggerBlobDownload(new Blob(['zip']), 'proyecto.zip', platform)

  assert.equal(anchor.href, 'blob:logicdraft')
  assert.equal(anchor.download, 'proyecto.zip')
  assert.deepEqual(events, ['create', 'append', 'click', 'remove', 'revoke:blob:logicdraft'])
})
