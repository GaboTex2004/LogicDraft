// Browser integration, no extra dependency. Start Vite on 5175 before running.
// All API requests are intercepted; never connects this fixture to a real database.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, dirname, basename, resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const base = process.env.TUTOR_TEST_URL || 'http://127.0.0.1:5175'
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(base).hostname), 'Use a local Vite test server')
await fetch(base)
const profile = await mkdtemp(join(tmpdir(), 'logicdraft-tutor-browser-'))
const browser = spawn(process.env.TUTOR_BROWSER || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', [
  '--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking',
  '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank',
], { windowsHide: true, stdio: 'ignore' })
let launchError
browser.on('error', error => { launchError = error })
let socket
try {
  let port
  for (let i = 0; i < 100; i++) {
    if (launchError) throw launchError
    try { port = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break } catch { await delay(100) }
  }
  assert.ok(port, 'Browser did not start')
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
  socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject })
  let sequence = 0
  const waiting = new Map()
  let closing = false
  socket.addEventListener('close', () => {
    // Chromium may close the transport before acknowledging Browser.close.
    if (closing) { for (const pending of waiting.values()) pending.resolve({}); waiting.clear() }
  })
  const commands = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence
    const timer = setTimeout(() => { waiting.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 10000)
    waiting.set(id, { resolve: result => { clearTimeout(timer); resolve(result) }, reject: error => { clearTimeout(timer); reject(error) } })
    socket.send(JSON.stringify({ id, method, params }))
  })
  let role = 'OWNER'
  let exercise = false
  const errors = [], mutations = []
  const project = { id: 10, workspaceId: 1, workspaceNombre: 'Prueba', nombre: 'Proyecto de prueba', descripcion: '', fechaCreacion: '2026-11-07' }
  socket.onmessage = event => {
    const message = JSON.parse(event.data)
    if (message.id) {
      const pending = waiting.get(message.id)
      waiting.delete(message.id)
      if (message.error) pending?.reject(new Error(JSON.stringify(message.error)))
      else pending?.resolve(message.result)
    }
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.text + JSON.stringify(message.params.exceptionDetails.exception))
    if (message.method === 'Fetch.requestPaused') {
      const { requestId, request } = message.params
      const path = new URL(request.url).pathname
      if (path.startsWith('/tutor-audio/')) { void commands('Fetch.continueRequest', { requestId }).catch(error => errors.push(String(error))); return }
      let body = [], status = 200
      // Existing editor cleanup releases its locks on unmount, independently of the tutor.
      if (exercise && request.method === 'POST' && path.endsWith('/proyectos')) body = { ...project, id: 12, nombre: JSON.parse(request.postData).nombre }
      else if (exercise && request.method === 'POST' && path.includes('/locks/')) body = { locks: [] }
      else if (exercise && request.method === 'PUT' && path.endsWith('/diagrama')) {
        const document = JSON.parse(request.postData)
        body = { id: 20, proyectoId: 10, revision: 2, version: document.version, contenido: { version: document.version, nodes: document.nodes, edges: document.edges }, fechaActualizacion: '2026-11-07' }
      }
      else if (request.method === 'POST' && path.endsWith('/proyectos/10/locks/release')) body = {}
      else if (request.method !== 'GET' && request.method !== 'OPTIONS') { mutations.push(path); status = 403 }
      else if (path.endsWith('/auth/me')) body = { id: 1, nombre: 'Prueba', email: 'test@example.invalid', rolSistema: 'USER' }
      else if (path.endsWith('/workspaces')) body = [{ id: 1, nombre: 'Workspace A', tenantId: 1, rol: role }, { id: 2, nombre: 'Workspace B', tenantId: 1, rol: 'VIEWER' }]
      else if (path.endsWith('/proyectos')) body = [project, { ...project, id: 11, nombre: 'Otro proyecto' }]
      else if (path.endsWith('/proyectos/10')) body = project
      else if (path.endsWith('/proyectos/12')) body = { ...project, id: 12, nombre: 'xcdf' }
      else if (path.endsWith('/diagrama')) body = { id: 20, proyectoId: path.includes('/12/') ? 12 : 10, revision: 1, version: 1, contenido: { version: 1, nodes: [], edges: [] }, fechaActualizacion: '2026-11-07' }
      else if (path.includes('/ws')) { status = 503; body = {} }
      void commands('Fetch.fulfillRequest', { requestId, responseCode: status, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }, { name: 'Access-Control-Allow-Origin', value: '*' }, { name: 'Access-Control-Allow-Headers', value: '*' }, { name: 'Access-Control-Allow-Methods', value: '*' }], body: Buffer.from(JSON.stringify(body)).toString('base64') }).catch(error => errors.push(String(error)))
    }
  }
  await commands('Runtime.enable')
  await commands('Page.enable')
  await commands('Fetch.enable', { patterns: [{ urlPattern: '*', resourceType: 'XHR' }, { urlPattern: '*', resourceType: 'Fetch' }] })
  await commands('Network.enable')
  await commands('Network.setBlockedURLs', { urls: ['ws://*/ws*', 'wss://*/ws*'] })
  await commands('Emulation.setDeviceMetricsOverride', { width: 1400, height: 1000, deviceScaleFactor: 1, mobile: false })
  await commands('Page.addScriptToEvaluateOnNewDocument', { source: "localStorage.setItem('token','fixture-only'); localStorage.setItem('logicdraft.tutor.position.v1',JSON.stringify({version:1,x:1300,y:900}));" })
  const evaluate = async expression => {
    const result = await commands('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails))
    return result.result.value
  }
  const until = async expression => {
    for (let i = 0; i < 150; i++) { if (await evaluate(expression)) return; await delay(100) }
    const diagnostic = await evaluate(`(async () => { const e=document.querySelector('.workspace-card a'); if(!e) return null; const {inspectTutorialControl}=await import('/src/features/tutor/services/inspectTutorialControl.ts'); return { inspected:inspectTutorialControl(e,true), bounds:e.getBoundingClientRect().toJSON(), parents:[e,...(function*(n){while(n.parentElement){n=n.parentElement;yield n}})(e)].map(n=>({tag:n.tagName,cl:n.className,box:n.getBoundingClientRect().toJSON(),overflow:getComputedStyle(n).overflow,opacity:getComputedStyle(n).opacity,visibility:getComputedStyle(n).visibility})),hit:document.elementFromPoint(e.getBoundingClientRect().x+e.clientWidth/2,e.getBoundingClientRect().y+e.clientHeight/2)?.outerHTML.slice(0,200)} })()`)
    const loading = await evaluate('({path:location.pathname,state:document.readyState,html:document.documentElement.outerHTML.slice(0,1000)})')
    throw new Error(`Condition failed: ${expression}\n${await evaluate('document.body.innerText.slice(0,2000)')}\n${JSON.stringify(errors)}\n${JSON.stringify(diagnostic)}\n${JSON.stringify(loading)}`)
  }
  const click = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`)
  const selectGuide = async index => {
    await click('.tutor-bubble')
    await until('!!document.querySelector(".tutor-guide-list")')
    await click(`.tutor-guide-list button:nth-child(${index})`)
  }
  await commands('Page.navigate', { url: `${base}/dashboard` })
  await until('document.querySelectorAll(".workspace-card").length === 1')
  await click('.tutor-bubble')
  await until('document.querySelector(".tutor-lesson h3")?.textContent === "Mi primer diagrama"')
  assert.equal(await evaluate('document.querySelector(".tutor-lesson select").options.length'), 3)
  assert.equal(await evaluate('document.querySelector(".tutor-lesson button[type=submit]").textContent'), 'Iniciar recorrido completo')
  await click('.tutor-close')
  await selectGuide(1)
  await evaluate('[...document.querySelectorAll(".tutor-back")].find(button => button.textContent.includes("Mi primer diagrama")).click()')
  await until('document.querySelector(".tutor-lesson h3")?.textContent === "Mi primer diagrama"')
  await click('.tutor-guide-list button:first-child')
  await until('document.querySelectorAll(".tutor-control-locator button").length === 1')
  await click('.tutor-control-locator button')
  await until('!!document.querySelector(".tutor-target-highlight")')
  assert.equal(await evaluate('document.querySelector(".tutor-target-highlight").getBoundingClientRect().left === document.querySelector(".workspace-card a").getBoundingClientRect().left'), true)
  await commands('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
  await until('!document.querySelector(".tutor-target-highlight")')
  console.log('PASS dashboard: workspace target, exact highlight, Escape')

  await click('.workspace-card a')
  await until('!!document.querySelector(".new-project-button")')
  await selectGuide(2)
  await until('document.querySelectorAll(".tutor-control-locator button").length === 1')
  await click('.tutor-control-locator button')
  await until('!!document.querySelector(".tutor-target-highlight")')
  await click('.new-project-button')
  await selectGuide(2)
  await until('document.querySelectorAll(".tutor-control-locator button").length === 3')
  await evaluate('document.querySelector("#project-name").disabled = true')
  await until('document.querySelectorAll(".tutor-control-locator button").length === 2')
  console.log('PASS projects: navigation, conditional form, disabled control')
  await evaluate('document.querySelector("#project-name").disabled = false')
  await click('.tutor-lesson button[type=submit]')
  await until('document.querySelector(".tutor-lesson").textContent.includes("Escribe el nombre que quieras")')
  await click('.tutor-close')
  await evaluate('document.querySelector("#project-name").focus()')
  for (const character of 'xcdf') await commands('Input.insertText', { text: character })
  await until('document.querySelector(".tutor-active-card").textContent.includes("Pulsa Crear proyecto")')
  await until('[...document.querySelectorAll(".tutor-target-highlight")].some(marker => { const button=document.querySelector("#project-name").form.querySelector("button[type=submit]"); return Math.abs(marker.getBoundingClientRect().top-button.getBoundingClientRect().top)<1 && Math.abs(marker.getBoundingClientRect().left-button.getBoundingClientRect().left)<1 })')
  await evaluate('document.querySelector(".tutor-active-card button").click()')
  console.log('PASS project name: real typing advances to Create project')

  await commands('Page.navigate', { url: `${base}/dashboard` })
  await until('!!document.querySelector(".workspace-card")')
  await click('.tutor-bubble')
  await until('!!document.querySelector(".tutor-lesson select")')
  await evaluate('const select=document.querySelector(".tutor-lesson select"); select.value="1"; select.dispatchEvent(new Event("change",{bubbles:true}))')
  await click('.tutor-lesson button[type=submit]')
  await evaluate('window.__originalAudio=window.Audio;window.Audio=class extends window.__originalAudio { constructor(){super();window.__tutorAudio=this;} }')
  await commands('Runtime.evaluate', { expression: '[...document.querySelectorAll(".tutor-voice-controls button")].find(b=>b.textContent.includes("Activar voz"))?.click()', userGesture: true })
  await until('!!window.__tutorAudio && window.__tutorAudio.src.includes("tutor-audio/")')
  await until('window.__tutorAudio.readyState >= 2')
  await evaluate('window.__tutorAudio.dispatchEvent(new Event("ended"))')
  assert.equal(await evaluate('document.querySelector(".tutor-lesson").textContent.includes("Pulsa Abrir workspace")'), true)
  await evaluate('window.dispatchEvent(new Event("logicdraft:microphone-start"))')
  await until('window.__tutorAudio.paused && [...document.querySelectorAll(".tutor-voice-controls button")].some(b=>b.textContent.includes("mientras grabas") && b.disabled)')
  await evaluate('window.dispatchEvent(new Event("logicdraft:microphone-end"))')
  await until('[...document.querySelectorAll(".tutor-voice-controls button")].some(b=>b.textContent.includes("Activar voz") && !b.disabled)')
  console.log('PASS Puck: WAV loads, end does not advance, microphone stops narration')
  await click('.tutor-close')
  await click('.workspace-card a')
  await until('document.querySelector(".tutor-active-card")?.textContent.includes("Pulsa Nuevo proyecto")')
  await click('.new-project-button')
  await until('document.querySelector(".tutor-active-card")?.textContent.includes("Escribe el nombre que quieras")')
  await evaluate('document.querySelector("#project-name").focus()')
  await commands('Input.insertText', { text: 'xcdf' })
  await until('document.querySelector(".tutor-active-card")?.textContent.includes("Pulsa Crear proyecto")')
  exercise = true
  await evaluate('document.querySelector("#project-name").form.requestSubmit()')
  await until('document.querySelector(".tutor-active-card")?.textContent.includes("Pulsa Abrir")')
  await until('[...document.querySelectorAll(".tutor-target-highlight")].some(marker=>{const b=document.querySelector(".project-card .project-button.primary");return Math.abs(marker.getBoundingClientRect().top-b.getBoundingClientRect().top)<1 && Math.abs(marker.getBoundingClientRect().left-b.getBoundingClientRect().left)<1})')
  assert.equal(await evaluate('document.querySelector(".project-card h2").textContent'), 'xcdf')
  await click('.project-card .project-button.primary')
  await until('!!document.querySelector(".diagram-toolbar") && document.querySelector(".tutor-active-card")?.textContent.includes("Crear una clase UML")')
  assert.equal(await evaluate('location.pathname'), '/proyectos/12/editor')
  await evaluate('document.querySelector(".tutor-active-card button").click()')
  console.log('PASS journey: arbitrary name, accepted creation, highlight Open on the new project')

  // Real DOM adapter checks in the browser, independent of platform data.
  const adapterChecks = await evaluate(`(async () => {
    const { inspectTutorialControl } = await import('/src/features/tutor/services/inspectTutorialControl.ts');
    const parent = document.createElement('div'); parent.style.cssText='position:fixed;left:5px;top:5px;width:100px;height:80px;z-index:9999';
    const button = document.createElement('button'); button.textContent='fixture'; parent.append(button); document.body.append(parent);
    const results = [inspectTutorialControl(button,true).visible];
    parent.style.opacity='0'; results.push(!inspectTutorialControl(button,true).visible); parent.style.opacity='1';
    parent.setAttribute('inert',''); results.push(!inspectTutorialControl(button,true).enabled); parent.removeAttribute('inert');
    button.setAttribute('aria-disabled','true'); results.push(!inspectTutorialControl(button,true).enabled); button.removeAttribute('aria-disabled');
    parent.style.height='1px'; parent.style.overflow='hidden'; results.push(!inspectTutorialControl(button,true).visible);
    parent.style.height='80px'; parent.style.overflow='visible';
    const cover=document.createElement('div');cover.style.cssText='position:fixed;left:5px;top:5px;width:100px;height:80px;z-index:10000';document.body.append(cover);
    results.push(!inspectTutorialControl(button,true).visible);cover.remove();
    parent.innerHTML='<svg width="100" height="80"><g><path class="react-flow__edge-interaction" d="M10 20 L70 20" stroke="black" stroke-width="24" fill="none" /></g></svg>';
    results.push(inspectTutorialControl(parent.querySelector('g'),true).visible);
    parent.remove(); results.push(!inspectTutorialControl(button,true).mounted); return results;
  })()`)
  assert.ok(adapterChecks.every(Boolean))
  console.log('PASS DOM adapter: opacity, inert, aria-disabled, clipping, occlusion, SVG, unmount')

  // At >1100px both editor panels are intentionally always visible.
  await commands('Emulation.setDeviceMetricsOverride', { width: 1000, height: 1000, deviceScaleFactor: 1, mobile: false })
  await commands('Page.navigate', { url: `${base}/proyectos/10/editor` })
  await until('!!document.querySelector(".diagram-toolbar")')
  if (await evaluate('document.querySelector(".diagram-panel-toggle").getAttribute("aria-pressed") === "true"')) await click('.diagram-panel-toggle')
  await delay(300)
  await selectGuide(4)
  await until('document.querySelectorAll(".tutor-control-locator button").length >= 1')
  const closedLabels = await evaluate('[...document.querySelectorAll(".tutor-control-locator button")].map(b=>b.textContent)')
  assert.ok(!closedLabels.some(label => label.includes('Clase UML')))
  await click('.tutor-close')
  await click('.diagram-panel-toggle')
  await selectGuide(4)
  await until('[...document.querySelectorAll(".tutor-control-locator button")].some(b=>b.textContent.includes("Clase UML"))')
  await evaluate('[...document.querySelectorAll(".tutor-control-locator button")].find(b=>b.textContent.includes("Clase UML")).click()')
  await until('!!document.querySelector(".tutor-target-highlight")')
  await evaluate('const modal=document.createElement("div");modal.id="fixture-modal";modal.setAttribute("role","dialog");modal.setAttribute("aria-modal","true");document.body.append(modal)')
  await until('!document.querySelector(".tutor-target-highlight") && document.querySelector(".tutor-host").hidden')
  await evaluate('document.querySelector("#fixture-modal").remove()')
  console.log('PASS editor: hidden panels, class control, modal priority')

  exercise = true
  await selectGuide(4)
  await evaluate('document.querySelector(".tutor-lesson button[type=submit]").click()')
  await until('document.querySelector(".tutor-lesson").textContent.includes("Pulsa Clase UML")')
  await click('.tutor-close')
  await evaluate('[...document.querySelectorAll(".add-entity-button")].find(b=>b.textContent.includes("Clase UML")).click()')
  await until('document.querySelector(".tutor-active-card").textContent.includes("Recorrido completado")')
  assert.equal(await evaluate('document.querySelectorAll(".entity-node").length'), 1)
  await evaluate('document.querySelector(".tutor-active-card button").click()')
  await commands('Emulation.setDeviceMetricsOverride', { width: 1400, height: 1000, deviceScaleFactor: 1, mobile: false })
  await selectGuide(7)
  await evaluate('const selects=document.querySelectorAll(".tutor-lesson select");selects[0].value=selects[0].options[1].value;selects[0].dispatchEvent(new Event("change",{bubbles:true}))')
  await evaluate('document.querySelector(".tutor-lesson input").focus()')
  await commands('Input.insertText', { text: 'id' })
  await evaluate('const select=[...document.querySelectorAll(".tutor-lesson select")].at(-1);select.value="INTEGER";select.dispatchEvent(new Event("change",{bubbles:true}))')
  await click('.tutor-lesson button[type=submit]')
  await click('.tutor-close')
  await until('document.querySelector(".tutor-active-card").textContent.includes("En Atributos, pulsa Agregar")')
  await evaluate('window.__attributeAudioBase=window.Audio;window.Audio=class extends window.__attributeAudioBase { constructor(){super();window.__attributeAudio=this;} }')
  await commands('Runtime.evaluate', { expression: '[...document.querySelectorAll(".tutor-active-card .tutor-voice-controls button")].find(b=>b.textContent.includes("Activar voz"))?.click()', userGesture: true })
  await until('!!window.__attributeAudio && window.__attributeAudio.readyState >= 2 && window.__attributeAudio.src.includes("tutor-audio/")')
  const attributeAddAudio = await evaluate('window.__attributeAudio.src')
  const expectAttributeVoice = async key => {
    const file = await evaluate(`fetch('/tutor-audio/manifest.json').then(r=>r.json()).then(m=>m.entries[${JSON.stringify(key)}].file)`)
    await until(`window.__attributeAudio.src.endsWith(${JSON.stringify(file)}) && window.__attributeAudio.readyState >= 2`)
  }
  await expectAttributeVoice('add_attribute/add')
  await evaluate('document.querySelector(".diagram-properties .attributes-heading button").disabled=true')
  await delay(350)
  assert.equal(await evaluate('window.__attributeAudio.src'), attributeAddAudio, 'temporary disabled state must not discard an instruction already started')
  await evaluate('document.querySelector(".diagram-properties .attributes-heading button").disabled=false')
  await click('.diagram-properties .attributes-heading button')
  await until('document.querySelector(".tutor-active-card").textContent.includes("Escribe id")')
  await until(`window.__attributeAudio.src !== ${JSON.stringify(attributeAddAudio)} && window.__attributeAudio.src.includes("tutor-audio/") && window.__attributeAudio.readyState >= 2`)
  await expectAttributeVoice('add_attribute/name')
  await click('.tutor-bubble')
  await evaluate('[...document.querySelectorAll(".tutor-lesson button")].find(b=>b.textContent==="Anterior").click()')
  await until('document.querySelector(".tutor-lesson").textContent.includes("Volviste al paso anterior")')
  await evaluate('[...document.querySelectorAll(".tutor-lesson button")].find(b=>b.textContent==="Reanudar guía").click()')
  await click('.tutor-close')
  await commands('Runtime.evaluate', { expression: '[...document.querySelectorAll(".tutor-active-card .tutor-voice-controls button")].find(b=>b.textContent.includes("Activar voz"))?.click()', userGesture: true })
  await click('.diagram-properties .attributes-heading button')
  await until('document.querySelector(".tutor-active-card").textContent.includes("Escribe id")')
  await evaluate('const input=[...document.querySelectorAll(".attribute-editor input[aria-label]")].at(-1);input.focus();input.select()')
  await commands('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 })
  await commands('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 })
  await delay(1800)
  assert.equal(await evaluate('document.querySelector(".tutor-active-card").textContent.includes("El editor no pudo guardar")'), false, 'an incomplete draft must not pause the tutor during automatic validation')
  assert.equal(await evaluate('[...document.querySelectorAll(".tutor-active-card .tutor-voice-controls button")].some(b=>b.textContent.includes("Silenciar voz"))'), true, 'voice preference survives closing the panel')
  await commands('Input.insertText', { text: 'id' })
  await until('document.querySelector(".tutor-active-card").textContent.includes("Selecciona INTEGER")')
  await expectAttributeVoice('add_attribute/type')
  await evaluate('{ const attributeTypeSelect=[...document.querySelectorAll(".attribute-editor select")].at(-1);attributeTypeSelect.value="INTEGER";attributeTypeSelect.dispatchEvent(new Event("change",{bubbles:true})); }')
  await until('document.querySelector(".tutor-active-card").textContent.includes("Recorrido completado")')
  await evaluate('document.querySelector(".tutor-active-card button").click()')
  await commands('Emulation.setDeviceMetricsOverride', { width: 1000, height: 1000, deviceScaleFactor: 1, mobile: false })
  console.log('PASS attributes: add, back, review, resume, add again, name and type')
  await selectGuide(11)
  await evaluate('document.querySelector(".tutor-lesson button[type=submit]").click()')
  await click('.tutor-close')
  await until('!document.querySelector(".save-diagram-button").disabled')
  await commands('Runtime.evaluate', { expression: '[...document.querySelectorAll(".tutor-active-card .tutor-voice-controls button")].find(b=>b.textContent.includes("Activar voz"))?.click()', userGesture: true })
  await expectAttributeVoice('verify_save/save')
  await click('.save-diagram-button')
  await until('document.querySelector(".tutor-active-card").textContent.includes("Recorrido completado")')
  await evaluate('document.querySelector(".tutor-active-card button").click()')
  console.log('PASS live guide: local class creation and confirmed save using simulated APIs')

  role = 'VIEWER'
  await commands('Emulation.setDeviceMetricsOverride', { width: 1400, height: 1000, deviceScaleFactor: 1, mobile: false })
  await commands('Page.navigate', { url: `${base}/workspaces/1/proyectos` })
  await until('!!document.querySelector(".new-project-button")')
  await selectGuide(2)
  await delay(500)
  assert.equal(await evaluate('document.querySelectorAll(".tutor-control-locator button").length'), 0)
  await click('.tutor-back')
  await click('.tutor-guide-list button:nth-child(3)')
  // The right-side tutor panel covers the second card: moving it must reveal that target.
  await until('document.querySelectorAll(".tutor-control-locator button").length === 1')
  await click('.tutor-close')
  await commands('Input.dispatchKeyEvent', { type: 'keyDown', key: 'F2', code: 'F2', windowsVirtualKeyCode: 113 })
  await until('document.querySelector(".tutor-bubble").classList.contains("is-positioning")')
  await evaluate('for(let i=0;i<45;i++) document.querySelector(".tutor-bubble").dispatchEvent(new KeyboardEvent("keydown",{key:"ArrowLeft",shiftKey:true,bubbles:true}))')
  await commands('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 })
  await selectGuide(3)
  await until('document.querySelectorAll(".tutor-control-locator button").length === 2')
  await click('.tutor-control-locator button:nth-of-type(2)')
  await until('!!document.querySelector(".tutor-target-highlight")')
  assert.equal(await evaluate('document.querySelector(".tutor-target-highlight").getBoundingClientRect().left === document.querySelectorAll(".project-card .project-button.primary")[1].getBoundingClientRect().left'), true)
  console.log('PASS VIEWER: no editing target, project navigation available')
  await commands('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await until('!document.querySelector(".tutor-target-highlight")')
  await click('.tutor-bubble')
  await until('!!document.querySelector(".tutor-panel")')
  assert.equal(await evaluate('document.querySelector(".tutor-panel").getBoundingClientRect().right <= innerWidth'), true)
  await commands('Page.navigate', { url: `${base}/login` })
  await until('!document.querySelector(".tutor-bubble")')
  console.log('PASS mobile resize and authenticated layout cleanup')
  assert.deepEqual(mutations, [], 'Tutor must not perform business writes')
  assert.deepEqual(errors, [], 'No unhandled browser errors')
  console.log('PASS no API mutations or unhandled errors; mocked API, real browser/UI')
  closing = true
  await commands('Browser.close')
} finally {
  socket?.close()
  browser.kill()
  await delay(500)
  // Only the isolated temporary profile created above; never a user profile.
  assert.equal(dirname(resolve(profile)), resolve(tmpdir()))
  assert.ok(basename(profile).startsWith('logicdraft-tutor-browser-'))
  await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 }).catch(() => {})
}
