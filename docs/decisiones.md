# Decisiones de tooling — gate O.5

Registro de habilitación de herramientas de terceros para este repositorio. Ninguna
herramienta se instala sin pasar por las 6 preguntas de abajo, respondidas con
verificación real (fuente consultada, no supuesta), antes de ejecutar el instalador.

Formato por herramienta: las 6 preguntas del gate, el resultado, y la decisión.

---

## Semgrep

- **Fuente verificada:** `pip install semgrep` — paquete oficial en PyPI, mantenido por
  Semgrep Inc. Ya instalado y en uso desde T-703 de la fase 7 (`.semgrep/tributary-rules.yml`).
- **Superficie de datos:** lee código fuente local, no persiste nada fuera de `~/.semgrep/`
  (cache de reglas). El modo CLI usado en este proyecto no requiere cuenta ni sincroniza
  hallazgos a la nube de Semgrep (no se corrió `semgrep login`).
- **STRIDE ligero:** no ejecuta comandos con input no confiable; analiza texto estáticamente.
- **Scope de contexto:** no aplica (herramienta CLI, no un skill que cargue contexto).
- **Conflicto con el mandato:** ninguno — es la herramienta que ya sostiene T-703/CV-13.
- **Registro:** habilitado. Sin reservas.

**Decisión: habilitado, ya en uso.**

---

## Trail of Bits Skills (`trailofbits/skills`) — variant-analysis, fix-verification

- **Fuente verificada:** `github.com/trailofbits/skills`, marketplace real, mantenido por
  Trail of Bits (firma de seguridad reconocida). **Hallazgo real durante la verificación:**
  existen varios forks/clones con nombres casi idénticos (`Neosprings/trailofbits-skills`,
  `johnniemorrow/trailofbits_skills`) que NO son el proyecto oficial — el patrón exacto de
  "clon con nombre casi igual" que este mismo gate existe para detectar. Solo el path
  `trailofbits/skills` (cuenta de organización `trailofbits`) es el oficial.
- **Superficie de datos:** el manifiesto real (`marketplace.json`) confirma 48 plugins
  instalables. **`variant-analysis` existe** (v2.0.1, autor Axel Mierczuk, "encuentra
  vulnerabilidades y bugs similares usando análisis de patrones" — lee código local).
  **`fix-verification` NO existe** en el marketplace real bajo ese nombre — el prompt que
  pidió instalarlo describía mal el catálogo real. Los más cercanos son `fp-check`
  (verificación de falsos positivos) y `skill-improver` (ninguno hace lo que se describió).
- **STRIDE ligero:** análisis de patrones sobre código local, sin ejecución de comandos
  con input no confiable.
- **Scope de contexto:** un plugin, no el marketplace completo.
- **Conflicto con el mandato:** ninguno.
- **Registro:** **BLOQUEADO ESTRUCTURALMENTE, no por decisión de riesgo** — `/plugin
  marketplace add` y `/plugin install` son comandos del binario interactivo `claude` (la
  app de terminal de Claude Code). Esta sesión corre sobre el Claude Agent SDK, sin ese
  binario disponible (`which claude` → no encontrado, sin `~/.claude/plugins/`). No hay
  forma de ejecutar este paso desde acá.

**Decisión: `variant-analysis` verificado como real y sin objeción de riesgo — pendiente,
a instalar por Luis desde una sesión interactiva de Claude Code
(`/plugin marketplace add trailofbits/skills` seguido de `/plugin install variant-analysis`).
`fix-verification` no existe — no se instala porque no hay qué instalar; si la intención
real era "fp-check" o "skill-improver", eso es una decisión aparte, no asumida acá.**

---

## ponytail (`DietrichGebert/ponytail`)

- **Fuente verificada:** `github.com/DietrichGebert/ponytail`, repositorio real,
  activamente adoptado. El propio autor retractó públicamente la cifra de eficiencia
  original ("80–94% menos código") por sobreestimada — señal de buena fe, no de fraude.
- **Superficie de datos:** un skill de texto (instrucciones), no un binario ni un
  proceso — no lee ni persiste nada por sí mismo más allá de los archivos que ya toca
  cualquier sesión de Claude Code.
- **STRIDE ligero:** sin superficie de ejecución propia; es una guía de decisión que el
  propio modelo sigue, no código de terceros corriendo con privilegios.
- **Scope de contexto:** un skill, carga acotada.
- **Conflicto con el mandato — el que motiva la reserva explícita del propio pedido:**
  el modo `ultra` cuestiona el requerimiento en la misma pasada que el código y puede
  clasificar controles de seguridad como "over-engineering". Este proyecto ES,
  fundamentalmente, controles de seguridad (triggers de inmutabilidad ADR-002, RBAC,
  cifrado por titular ADR-004) — exactamente lo que `ultra` tiende a recortar. **Reserva
  no negociable: intensidad `full` (default) únicamente, nunca `ultra`, sin excepción.**
- **Registro:** habilitado con la reserva de arriba, verificable en cualquier sesión
  futura revisando qué intensidad se invocó.

**Decisión: habilitado. `full` únicamente.**

---

## claude-mem (`thedotmack/claude-mem`)

- **Fuente verificada:** paquete real en npm (`claude-mem`, última versión reciente),
  repositorio real en GitHub, documentación en `docs.claude-mem.ai`. Usa ChromaDB local
  para almacenamiento vectorial y el Claude Agent SDK para comprimir observaciones.
- **Superficie de datos:** captura uso de herramientas durante la sesión, lo comprime y
  lo inyecta en sesiones futuras. **Hallazgo real durante la verificación:** la
  documentación pública consultada no menciona ninguna función de sincronización a la
  nube — la reserva del prompt original ("confirmá que cloud-sync está en false") asume
  una funcionalidad que la documentación no confirma que exista. Verificado empíricamente
  contra el `settings.json` real generado — ver más abajo.
- **STRIDE ligero:** riesgo real si el proyecto SÍ tuviera sync remoto no documentado —
  este repo maneja PII de compradores (A-003) y credenciales de Factus (A-002); cualquier
  persistencia fuera de esta máquina sería una fuga por diseño. Mitigado verificando el
  archivo de configuración real después de instalar, no solo la documentación.
- **Scope de contexto:** hooks + un servicio worker local; no es un skill de contexto.
- **Conflicto con el mandato:** ninguno identificado, sujeto a la verificación empírica.
- **Registro:** ver la entrada de instalación en `tasks/todo.md` / el resumen de cierre
  de esta sesión para el resultado real de inspeccionar `~/.claude-mem/settings.json`.

**Decisión: habilitado. Verificación empírica post-instalación real (no solo documentación):**
`~/.claude-mem/settings.json` generado contiene únicamente `{"CLAUDE_MEM_RUNTIME": "worker"}`
— **no existe ninguna clave de `cloud-sync`**, porque la herramienta no tiene esa
funcionalidad en absoluto (confirmado contra el archivo real, no solo contra la
documentación). La reserva original del prompt asumía una función que este tool no tiene.
**Hallazgo real que sí importa, y que el prompt original no preguntó:** el código
instalado (`dist/index.js`) muestra que la función de compresión de observaciones llama a
`https://api.anthropic.com/v1/messages` (y opcionalmente a la API de Gemini si se
configura) — es decir, el contenido de la sesión capturada (lecturas de archivo, comandos)
sí sale de la máquina hacia una API de LLM para resumirse, aunque no hacia un backend
propio de "cloud sync". Es el comportamiento esperado de una herramienta de memoria
potenciada por IA, no una puerta trasera oculta, pero es la pieza de superficie de datos
real a registrar — no "sin sync a la nube" en sentido absoluto, sino "sin sync de base de
datos propia, con egress hacia la API de LLM que la comprime". `telemetry.json` trae un
`installId` random y `decidedAt` vacío (el prompt de consentimiento de telemetría se
saltó por sesión no interactiva) — sin endpoint de analítica de terceros encontrado en el
código instalado (sin PostHog/Sentry ni similar).

---

## ECC (`affaan-m/ECC`)

- **Fuente verificada:** repositorio real, extremadamente popular. **El propio proyecto
  confirma por escrito** ("Third-party re-uploads and unofficial mirrors are not
  maintained or reviewed by the project and may contain malware") lo que el prompt
  original citaba — no es una afirmación inventada por el prompt, es real y documentada
  por el propio proyecto, incluida una auditoría externa que encontró un clon malicioso
  circulando. Canales oficiales confirmados: `github.com/affaan-m/ECC`, paquetes npm
  `ecc-universal` y `ecc-agentshield`, la GitHub App, el slug de plugin `ecc@ecc`, y
  `ecc.tools`.
- **Hallazgo real — el comando pedido no es el comando real:** el prompt original decía
  `npx ecc install --profile security --target claude`. Verificado contra la
  documentación real: **el paquete npm es `ecc-universal`, no `ecc`** — un paquete
  llamado literalmente `ecc` en el registro de npm no es del proyecto y podría ser
  cualquier cosa, incluido un nombre ocupado por otro actor (typosquatting). Además, el
  setup guiado por `npx` recién llega en la versión 2.2.0 — la 2.1.0 actual no lo trae.
  El camino real para Claude Code es `/plugin marketplace add https://github.com/affaan-m/ECC`
  seguido de `/plugin install ecc@ecc`.
- **Superficie de datos:** según su propia documentación, incluye "AgentShield security
  scanning" con más de 1200 tests que auditan hooks, configuración MCP y definiciones de
  agente — funcionalidad orientada a defensa, no solo carga de skills.
- **STRIDE ligero:** el riesgo real y documentado es exactamente el de un mirror no
  oficial con malware — mitigado usando únicamente los canales listados arriba, nunca
  un nombre de paquete corto sin verificar.
- **Scope de contexto:** el proyecto real ofrece 68 agentes y 284 skills — cargar todo
  satura el contexto, tal como advertía el prompt original; el mecanismo real de
  restricción no se pudo confirmar como un flag `--profile security` de instalación
  (no confirmado en la documentación consultada) sino que probablemente vive en la
  configuración post-instalación de la propia herramienta.
- **Conflicto con el mandato:** ninguno adicional al ya señalado.
- **Registro:** **BLOQUEADO ESTRUCTURALMENTE por la misma razón que Trail of Bits
  Skills** — la instalación real pasa por `/plugin marketplace add` / `/plugin install`,
  no disponible en esta sesión (sin binario `claude`). El comando `npx ecc install ...`
  tal como se pidió originalmente **no se ejecuta bajo ninguna circunstancia**: apunta a
  un nombre de paquete que no es el oficial verificado.

**Decisión: NO instalado. Pendiente de Luis desde una sesión interactiva de Claude Code,
usando exclusivamente el path oficial confirmado arriba (`ecc@ecc` vía `/plugin`, o el
paquete npm `ecc-universal` si en el futuro trae el setup guiado) — nunca `npx ecc`.**

---

## strix-agent

- **Fuente verificada:** paquete real en PyPI (`strix-agent`), repositorio real
  (`github.com/usestrix/strix`), Apache 2.0, adopción real (43k+ estrellas).
- **Superficie de datos:** agente autónomo de pentesting — incluye proxy de
  intercepción HTTP, explotación de navegador, ejecución de shell y comandos, runtime
  de exploits a medida. Requiere Docker y una API key de LLM propia.
- **STRIDE ligero — el más alto de todas las herramientas de esta lista:** ejecuta
  comandos y explota activamente lo que se le apunte. Correrlo (no solo instalarlo)
  contra cualquier endpoint con efectos reales sin el protocolo del PASO 4 es exactamente
  el incidente que el propio prompt cita como precedente.
- **Scope de contexto:** agente autónomo separado vía Docker, no carga skills en esta
  sesión.
- **Conflicto con el mandato:** ninguno en la instalación; el riesgo está enteramente en
  la ejecución, ya cubierto por el protocolo de neutralización de efectos.
- **Registro:** instalado (`pipx install strix-agent`) y **no ejecutado**, tal como pide
  el prompt original. Nota temporal real: la Fase 7 (protocolo §9B) ya se completó en
  esta misma sesión usando `sqlmap` directamente (T-702/T-708) — el gate "esperar a fase
  7" ya no aplica hacia adelante del mismo modo que cuando se escribió el pedido. Correr
  `strix` contra este sistema, si se decide hacerlo, necesita su propia autorización
  explícita y separada, con el protocolo del PASO 4 confirmado de nuevo para esa corrida
  específica — no se dispara solo porque la herramienta ya está instalada.

**Decisión: instalado, sin ejecutar. Ejecutarlo requiere autorización separada.**

---

## agent-browser (`vercel-labs/agent-browser`)

**Nota de contexto:** el pedido original de tooling excluía esta herramienta explícitamente, con
un motivo correcto en su momento: *"el proyecto no tiene UI (ADR-006), sería una herramienta sin
tarea que la use."* Ese motivo **caducó** al aprobarse ADR-010 — ahora existe `tributary-web` y
la herramienta tiene una tarea real (T-806, E2E contra la interfaz de verdad y no solo por
terminal). Se registra el cambio de circunstancia en vez de instalarla en silencio.

- **Fuente verificada:** `github.com/vercel-labs/agent-browser` (Vercel Labs), paquete npm oficial
  `agent-browser` (0.34.0). CLI nativa en Rust. El binario de Chrome lo descarga
  `agent-browser install` desde `storage.googleapis.com/chrome-for-testing-public`, el canal
  oficial de Chrome for Testing.
- **Superficie de datos:** controla un Chromium **local**; capturas y árboles de accesibilidad se
  quedan en la máquina. Existen proveedores de navegador en la nube (Browserbase, Browserless,
  AgentCore) que son **opcionales** y exigen credenciales propias — no se configuran, así que no
  hay tránsito de datos hacia terceros.
- **STRIDE ligero:** conduce un navegador, no ejecuta comandos de shell con entrada no confiable.
  El riesgo real es de dirección: apuntada a una página con credenciales podría capturarlas en un
  screenshot. Acotado por uso: solo se apunta a la instancia local de demo, cuyos tokens son
  públicos por diseño (ADR-010) y no protegen nada real.
- **Scope de contexto:** CLI, no un skill que cargue contexto.
- **Conflicto con el mandato:** ninguno — su modo por defecto ya es local.
- **Registro:** esta entrada.

**Decisión: habilitada, uso local únicamente (sin proveedores de navegador en la nube).**
