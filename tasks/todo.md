# tasks/todo.md — Tributary

**SRS:** `docs/SRS-tributary.md` v1.0 (Approved)
**Milestone activo:** M1 — sistema completo en `docker-compose` local
**Milestone 2:** exposición pública. No se inicia sin superar el gate de SRS §10.5.

Estado: `[ ]` pendiente · `[~]` en progreso · `[x]` hecho y verificado · `[!]` bloqueado

Una tarea solo pasa a `[x]` cuando su criterio de verificación se ejecutó y produjo el resultado esperado. "Compila" y "el test pasa" no son criterios si el criterio dice otra cosa.

---

## Fase 0 — Cimientos del repositorio

- [x] **T-000** Crear repositorio `tributary` bajo `Luisceron0`, privado hasta la fase 7
  - Verificación: el repo existe y `LICENSE` (Apache-2.0) y `NOTICE` están en la raíz
  - El repo existe (`Luisceron0/Tributary`). `LICENSE` Apache-2.0 en la raíz, descargado del origen oficial (`sha256 cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`, 11358 bytes). `NOTICE` en la raíz, con la declaración de límites de ADR-005. `README.md` mínimo y honesto (el definitivo es T-706)
  - Visibilidad: `gh repo view --json visibility` → `PRIVATE` (verificado 2026-08-15, tras acción manual de Luis; el token del entorno no administra el repo). Se revisa de nuevo en la fase 7 antes de publicar
- [x] **T-001** `.gitignore` con `.env`, `*.p12`, `*.pem`, `target/` **antes del primer commit de código**
  - Verificación: `git check-ignore -v .env` devuelve la regla
  - Ejecutado: `.env` → `.gitignore:4`, `.env.local` → `:5`, `*.p12` → `:7`, `*.pem` → `:8`, `target/` → `:17`. Añadidos además `*.key/*.jks/*.keystore/*.pfx`, `secrets/`, y `validator/*.jar|*.zip` conservando `validator/*.sha256` versionado (ADR-008 / T-012)
- [x] **T-002** Hook de pre-commit con gitleaks
  - Verificación: un commit con un secreto de prueba es rechazado localmente
  - Riesgo R-01: las credenciales de sandbox ya existen; esta tarea va antes que cualquier código que las use
  - gitleaks 8.30.1 instalado por `scripts/install-gitleaks.sh` con versión y SHA-256 fijados en el repo y verificados antes de desempaquetar (mismo criterio que CV-11). Hook versionado en `.githooks/pre-commit` vía `core.hooksPath`
  - Verificado en tres modos, todos con salida real: (1) `FACTUS_CLIENT_SECRET=…` staged → `EXIT CODE: 1`, `HEAD` sin avanzar; (2) gitleaks ausente del `PATH` → `EXIT CODE: 1` (fail-closed); (3) los 11 archivos reales de fase 0 staged → `EXIT CODE: 0`
  - **El hook falló abierto en el primer intento** (imprimía el rechazo y salía 0). Corregido y re-verificado. Ver L-008
  - Cobertura medida del config por defecto: detecta `client_secret` en forma `ENV=`, YAML y properties de Spring, y PAT de GitHub. **No** detecta claves AWS. Irrelevante hoy (no se usa AWS); si alguna vez entra un secreto de forma no cubierta, hace falta regla propia en `.gitleaks.toml`
- [x] **T-003** Esqueleto Maven multi-módulo con los siete módulos de SRS §6.2
  - Verificación: `mvn -q compile` verde y `tributary-domain/pom.xml` sin ninguna dependencia declarada
  - `mvn -q compile` → `EXIT CODE: 0`; reactor con los 7 módulos en `SUCCESS`
  - `grep -c "<dependenc" tributary-domain/pom.xml` → `0`. `mvn -pl tributary-domain dependency:list -DincludeScope=runtime` → `none`
  - Acuerdo A-1: JUnit 5.12.2 + jqwik 1.9.3 + ArchUnit 1.4.1 se declaran en el POM padre en `<scope>test</scope>`, heredados por todos los módulos, para que el POM del dominio no lleve bloque `<dependencies>`. Versiones fijadas, sin rangos (§5.3)
  - Acuerdo A-5: `maven.compiler.release=21` fijado explícitamente (el toolchain local es JDK 25; manda el SRS §8)
  - Acuerdo A-2: `ArchitectureTest` irá en `tributary-api/src/test/java/com/tributary/architecture/` (único módulo que ve a todos). Directorio ya creado. No se añade un octavo módulo
  - Pendiente deliberado: no se declaró ninguna dependencia real (Spring Boot, Flyway, JAXB, driver JDBC). Cada una entra con la tarea que la necesita y con su justificación en el README

---

## Casos de referencia — RC-1, RC-2, RC-3

Decididos el 2026-08-15. El SRS los invoca como *"los tres casos de prueba definidos"* (RF-005) y *"los tres XML de referencia"* (CV-05) pero nunca los define; esto cierra ese vacío. Son el alcance de T-101 (qué BT/BG se modelan), de T-104 (qué subconjunto `BR-*` se implementa) y de T-505/CV-05 (qué se valida contra KoSIT). **Ampliar el modelo más allá de lo que estos tres casos exigen es ampliar el alcance de §3.**

| ID | Caso | Composición | Qué ejercita |
|----|------|-------------|--------------|
| **RC-1** | Estándar, una línea | 1 línea · categoría `S` · 19 % · sin descuentos | BT-106/109/112, BR-CO-10. La línea base: si esto falla, nada más importa |
| **RC-2** | Multi-línea, multi-tipo, con descuentos | 3 líneas · `S` 19 % + `S` 7 % · descuento de línea y de documento | Dos grupos BG-23, BT-92, BT-107. **Aquí es donde rompe `HALF_UP`** — es el objetivo real de las propiedades jqwik de T-100 |
| **RC-3** | Entrega intracomunitaria, inversión del sujeto pasivo | 2 líneas · categoría `AE` · 0 % · motivo de exención obligatorio | `BR-AE-*`, BT-120/BT-121, VAT ID del comprador obligatorio. Es la venta B2B transfronteriza del §1: sin este caso el sistema valida solo facturas domésticas y pierde su tesis |

---

## Fase 1 — Dominio y puertos (día 1) · bloquea todo lo demás

- [x] **T-100** Value objects `Money`, `TaxRate`, `Quantity` con `BigDecimal` escala 2, `HALF_UP`
  - Verificación: test de propiedades jqwik — para cualquier combinación, base + impuesto = total con escala 2
  - `mvn -pl tributary-domain test` → `Tests run: 27, Failures: 0, Errors: 0` · `BUILD SUCCESS`. 10 propiedades jqwik × 1000 casos cada una
  - TDD: los cuatro archivos de test se escribieron primero y se vieron fallar (`cannot find symbol: class Money / TaxRate`) antes de existir la implementación
  - **Prueba de falsabilidad ejecutada (L-004):** con `ROUNDING = HALF_EVEN` la suite falla — `Tests run: 27, Failures: 3` en `MoneyTest.roundsHalfUpAwayFromZero`, `MoneyTest.multiplicationRounds` y `TaxRateTest.computesTaxOnBase`. Revertido a `HALF_UP` y re-verificado en verde
  - ⚠️ **Las 10 propiedades pasaron bajo `HALF_EVEN`** (`Tests run: 10, Failures: 0`). El criterio de verificación de esta tarea, tal como está escrito, no detecta una deriva del modo de redondeo. Ver L-012
  - `Quantity` va a escala 6, no 2: no es dinero ni impuesto, y forzarla a 2 corrompería `cantidad × precio` en unidades fraccionarias. El redondeo a escala 2 ocurre una sola vez, al calcular el importe de línea
  - `Money` lleva `java.util.Currency` (JDK, no es dependencia externa) y rechaza operar entre monedas distintas con `IllegalArgumentException`
  - Sin `double` ni `float` en el módulo. Se verificará además por ArchUnit en T-105
- [ ] **T-101** Modelo EN 16931: `Invoice`, `InvoiceLine`, `Buyer`, `Issuer` con términos BT/BG
  - Verificación: cada campo del modelo mapea a un BT/BG documentado en un comentario o en el README
- [ ] **T-102** Máquina de estados `DRAFT → SUBMITTING → ISSUED | ISSUED_WITH_WARNINGS | REJECTED | NEEDS_RECONCILIATION → MANUAL_REVIEW`
  - Verificación: test exhaustivo de transiciones; toda transición no declarada lanza excepción
  - Nota: `MANUAL_REVIEW` no tiene transición automática de salida (RF-008)
- [ ] **T-103** Puerto `FiscalRegimePort` con `issue`, `cancel`, `query`
  - Verificación: la interfaz no menciona ningún tipo de ningún régimen concreto
- [ ] **T-104** Reglas de negocio EN 16931 aplicables (subconjunto `BR-*` de los tres casos de referencia)
  - Verificación: un documento que viola `BR-CO-10` es rechazado en el dominio, sin llegar al adaptador
  - Alcance = el que exigen RC-1, RC-2 y RC-3, y ni una regla más
  - ⚠️ **`BR-CO-10` no tiene nada que ver con Colombia.** En EN 16931, `BR-CO-*` es la familia de reglas de *cálculo* (**CO**ndition); `BR-CO-10` es "la suma de los importes netos de línea = BT-106", que es lo que RF-005 confirma al glosarla como "(suma de bases)". La colisión con el código de país `CO` usado en `tributary-adapter-co-factus` y en "régimen CO" es accidental y peligrosa: una regla de cálculo europea leída como una regla colombiana termina implementada en el adaptador equivocado. En el código se comenta la expansión la primera vez que aparece
- [ ] **T-105** Test ArchUnit: el dominio no importa Spring, Jackson, JDBC ni adaptadores — **CV-07**
  - Verificación: `mvn test -Dtest=ArchitectureTest` con 0 violaciones
  - Vive en `tributary-api/src/test/java/com/tributary/architecture/ArchitectureTest.java` (acuerdo A-2: único módulo con visibilidad de los siete)
  - **Ampliación acordada (sesión 2026-08-15).** La regla de imports no ve un campo `private String cufe`. Se añaden dos reglas más:
    - **R-lexemas:** falla si aparece `cufe`, `referenceCode`/`reference_code`, `numberingRange`/`numbering_range`, `factus`, `dian`, `aeat`, `verifactu`, `xrechnung` o `kosit` en nombres de tipo, campo o método dentro de `tributary-domain`. Cierra el hueco de L-002, que hasta ahora era una "revisión de nombres" manual sin criterio binario
    - **R-adaptadores:** falla si un adaptador importa clases de otro adaptador. Hoy nada lo verifica y es un defecto aunque compile
  - Las tres reglas se observan fallando en rojo antes de darse por buenas (L-004)
- [ ] **T-106** Caso de uso RF-001 con `businessKey` determinístico
  - Verificación: dos ejecuciones idénticas producen la misma clave; conteo en repositorio = 1
  - **Derivación decidida el 2026-08-15:** `businessKey = SHA-256(issuerId ‖ saleId)`, donde `saleId` es un identificador externo de la venta, **obligatorio en el request**. RF-001 dice "derivado determinísticamente de la venta" sin decir de qué campos; esto lo fija
  - Motivo del descarte del hash de contenido: dos pedidos genuinamente distintos con las mismas líneas el mismo día colapsarían en una sola clave, y el sistema se negaría a emitir la segunda factura sin forma de forzarla. La identidad de una venta la declara el llamante, no la infiere el contenido
  - Consecuencia en el contrato: `POST /api/v1/invoices` gana un campo obligatorio `saleId`. Actualizar §6.5 y el OpenAPI de T-707
  - Test negativo: un `saleId` ausente o vacío es `422`, nunca una clave generada por defecto. Un `businessKey` con un componente aleatorio o temporal rompe ADR-003 entero

---

## Fase 2 — Persistencia e integridad (día 2) · bloquea fases 3 y 4

- [ ] **T-200** Migración Flyway: esquema de SRS §6.4
  - Verificación: `flyway migrate` sobre PostgreSQL 16 limpio, sin errores
- [ ] **T-201** Trigger `BEFORE INSERT` de validación de encadenamiento en `fiscal_record`
  - Verificación: insertar un registro con `previous_hash` incorrecto es rechazado por la base de datos
- [ ] **T-202** Trigger de inmutabilidad: rechazar `UPDATE` sobre registro encadenado — **CV-02**
  - Verificación: `UPDATE fiscal_record SET payload='x' WHERE id=...;` desde `psql` devuelve `ERROR`, 0 filas afectadas
  - Evidencia: captura del error de `psql`
- [ ] **T-203** `CHECK` de coherencia de estado: `ISSUED` implica `cufe IS NOT NULL AND number IS NOT NULL`
  - Verificación: insertar el estado imposible es rechazado
- [ ] **T-204** Rol de aplicación con `REVOKE UPDATE, DELETE` sobre `fiscal_record` y `audit_event`
  - Verificación: la aplicación falla al intentar un `UPDATE`, incluso con el trigger deshabilitado
- [ ] **T-205** Bloqueo consultivo por `chainId` para inserción serializada
  - Verificación: 20 hilos insertando en la misma cadena producen una secuencia sin huecos ni duplicados
- [ ] **T-206** Verificador de cadena RF-006 con usuario de base de datos de solo lectura
  - Verificación: 1.000 registros sanos → `INTACT` en < 2 s
- [ ] **T-207** Test de detección de manipulación — **CV-03**
  - Verificación: con el trigger deshabilitado, alterar un registro intermedio hace que el verificador devuelva `BROKEN` señalando ese registro exacto
  - Evidencia: salida del verificador. **Esta es la captura principal del portafolio**
- [ ] **T-208** Tests con Testcontainers para todo lo anterior
  - Verificación: la suite corre contra PostgreSQL real, no H2

---

## Fase 3 — Adaptador CO / Factus (día 3) · paralelizable con fase 4

- [ ] **T-300** Cliente OAuth2 con refresh de vuelo único
  - Verificación: 20 hilos con token expirado disparan exactamente una petición de refresh
- [ ] **T-301** Limitador saliente a 60 req/min con ventana deslizante
  - Verificación: 200 peticiones seguidas sin un solo 429; ninguna ventana de 60 s supera 60 peticiones
- [ ] **T-302** Manejo de `429`: respetar `Retry-After` con jitter, registrar como incidente
  - Verificación: forzado con WireMock, el reintento ocurre después del tiempo indicado
- [ ] **T-303** Traducción del dominio al payload de Factus
  - Verificación: el JSON generado valida contra el ejemplo de referencia campo a campo
- [ ] **T-304** Caso de uso RF-002 con transición confirmada antes de la E/S de red
  - Verificación: el estado `SUBMITTING` es visible desde otra conexión antes de que la petición salga
- [ ] **T-305** Manejo de los cuatro resultados: `201` limpio, `201` con `errors`, `is_validated=false`, timeout
  - Verificación: contract tests con WireMock para los cuatro; `errors` se preserva íntegro
- [ ] **T-306** Reconciliador RF-008: consulta obligatoria antes de reintentar
  - Verificación: test con mock que afirma el **orden** de llamadas — ninguna emisión sin consulta previa
- [ ] **T-307** Test de caos — **CV-10**
  - Verificación: matar el proceso tras el envío, reiniciar, listar por `reference_code` en Factus → exactamente 1 documento
  - Evidencia: log del proceso muerto + listado del sandbox
- [ ] **T-308** Test de concurrencia: 20 hilos sobre el mismo documento → 1 emisión
- [ ] **T-309** Guarda de entorno fail-closed
  - Verificación: el servicio se niega a arrancar contra la URL de producción sin la variable de habilitación explícita

---

## Fase 4 — Adaptador ES / Verifactu (día 4) · paralelizable con fase 3

- [ ] **T-400** Canonicalización de campos del registro de alta según RD 1007/2023
  - Verificación: la huella es reproducible desde los datos persistidos
- [ ] **T-401** Cálculo de huella SHA-256 incorporando la huella previa
- [ ] **T-402** Registro de anulación vinculado al de alta (RF-004)
  - Verificación: tras anular, el verificador sigue devolviendo `INTACT`
- [ ] **T-403** Generación del QR apuntando al verificador propio — **CV-12** (ADR-007)
  - Verificación: test que falla si aparece cualquier host de la AEAT en el contenido del QR
  - Leyenda de modo no remitido presente
- [ ] **T-404** Endpoint `GET /api/v1/records/{id}/verification`
  - Verificación: devuelve registro, huella, posición en cadena y declaración de modo no remitido
  - **Resuelto por ADR-009 (SRS v1.1), sin decisión pendiente.** Es la **única ruta pública sin autenticación** de todo el sistema. Cuerpo restringido a `{recordId, hash, previousHash, chainPosition, issuedAt, nonSubmittedNotice}` — sin PII, sin importes, sin identificadores fiscales
  - Test negativo obligatorio: cualquier campo fuera de esos seis en la respuesta hace fallar el test. Un endpoint público que crece por conveniencia es una fuga de PII con revisión previa aprobada
  - ⚠️ Ver bloqueante B-02: `docs/SRS-tributary.md` en el árbol de trabajo sigue en v1.0 y no contiene ADR-009 ni la fila de §6.5
- [ ] **T-405** Test negativo: no existe ninguna ruta de API que modifique un documento emitido
  - Verificación: barrido de todos los endpoints; ningún `PUT`/`PATCH` sobre facturas o registros

---

## Fase 5 — Adaptador DE / XRechnung (día 5) · paralelizable con fases 3 y 4

- [ ] **T-500** `SecureXmlFactory`: única vía de creación de parsers
  - DTD off, entidades externas off, `FEATURE_SECURE_PROCESSING` on, límites de tamaño y profundidad
- [ ] **T-501** Regla ArchUnit: prohibido instanciar parsers XML fuera de la factoría
  - Verificación: la regla falla en rojo cuando se introduce deliberadamente una instanciación directa
- [ ] **T-502** Sonda XXE — **CV-04**
  - Verificación: documento con entidad externa apuntando a `file:///etc/passwd` → excepción de parseo, sin lectura de archivo, sin conexión saliente observada
  - Evidencia: log del parser + captura de tráfico vacía
- [ ] **T-503** Mapeo del dominio a CII / XRechnung
- [ ] **T-504** Integración del validador KoSIT con versión fijada y `kosit.sha256` — **CV-11** (T-012)
  - Verificación: el build falla si el checksum no coincide
- [ ] **T-505** Validación de los tres XML de referencia — **CV-05**
  - Verificación: 0 errores fatales en el informe de KoSIT
  - Evidencia: informe del validador
- [ ] **T-506** Allowlist de egress
  - Verificación: ninguna URL contenida en un documento entrante es dereferenciada nunca (T-005)

---

## Fase 6 — Privacidad y auditoría (día 6)

- [ ] **T-600** `KeyVaultPort` con clave por titular
- [ ] **T-601** Cifrado AES-256-GCM con IV aleatorio por operación sobre nombre, dirección, correo y teléfono
  - Nota: identificador fiscal y país quedan en claro — son necesarios para la validez del documento
- [ ] **T-602** Crypto-shredding RF-007
  - Verificación: tras la supresión, `pg_dump | grep` no encuentra el texto claro
  - Verificación: RF-006 sigue devolviendo `INTACT`
- [ ] **T-603** Bitácora append-only con actor tomado del token, nunca del cuerpo
- [ ] **T-604** RBAC completo con los tres roles
- [ ] **T-605** Matriz rol × endpoint — **CV-08**
  - Verificación: `OPERATOR` recibe 403 en supresión; `AUDITOR` recibe 403 en emisión
- [ ] **T-606** Verificación de algoritmo JWT — **CV-09**
  - Verificación: token con `alg: none` y token HS256 firmado con la clave pública → 401 ambos

---

## Fase 7 — Endurecimiento y documentación (día 7)

- [ ] **T-700** Cabeceras de respuesta y CORS con allowlist explícita
- [ ] **T-701** Logging estructurado JSON con redacción de PII y secretos
  - Verificación: ningún log contiene identificadores fiscales, tokens ni payloads completos
- [ ] **T-702** Auditoría de SQLi incluyendo cabeceras — **CV-01**
  - Verificación: `sqlmap` con `--level 3 --risk 2` fuzzeando `X-Forwarded-For` y `User-Agent` → "not injectable" en todos los parámetros y cabeceras
  - Evidencia: salida de sqlmap
- [ ] **T-703** Reglas Semgrep propias, validadas primero contra código deliberadamente vulnerable
  - Verificación: cada regla se observó fallando en rojo antes de darse por buena
- [ ] **T-704** Pipeline de CI con SAST, SCA, gitleaks, ArchUnit y la matriz §9A
- [ ] **T-705** `docker compose up` desde clon limpio en < 3 min con datos de ejemplo
  - Verificación: ejecutado en una máquina sin caché
- [ ] **T-706** README y ADRs en inglés
  - Abre con la tesis y con la evidencia de CV-03, no con el stack (riesgo R-05)
  - Declara los límites de alcance con las palabras de ADR-005. **Nunca la palabra "compliant" ni "certified"** (riesgo R-03)
- [ ] **T-707** OpenAPI 3.1 generada y versionada
- [ ] **T-708** Protocolo pre-producción SRS §9B completo
  - Verificación: las cinco fases en verde, con evidencia de las diez tareas marcadas `CV-*`
- [ ] **T-709** Ficha del proyecto para el portafolio, en inglés

---

## Orden de recorte acordado

Si el presupuesto de siete días se agota, se recorta en este orden y no en otro:

1. Serialización a PDF/A-3 de ZUGFeRD — se conserva solo XRechnung XML
2. Fase 6 completa (crypto-shredding)

**Las fases 1, 2 y 4 no se recortan.** Contienen la tesis del proyecto.

---

## Bloqueantes activos

### ~~B-01 · El repositorio está público y T-000 exige privado hasta la fase 7~~ — CERRADO
**Abierto y cerrado 2026-08-15 · Owner: Luis**

El repositorio estaba `PUBLIC` y el token del entorno no podía cambiarlo (`HTTP 403: Resource not accessible by integration`). Luis lo cambió manualmente. **Cerrado con evidencia:** `gh repo view Luisceron0/Tributary --json visibility,isPrivate` → `{"isPrivate":true,"visibility":"PRIVATE"}`.

Se cerró antes de que hubiera un solo secreto en juego: en el momento del cierre el historial seguía siendo `64f3cd4 Initial commit` y `gitleaks dir .` daba 0 hallazgos. Vuelve a evaluarse en la fase 7, que es cuando el repositorio se hace público a propósito — y ahí el criterio ya no es este, es §10.5.

### B-02 · El SRS del árbol de trabajo está en v1.0; las instrucciones se refieren a v1.1
**Abierto 2026-08-15 · Severidad: BAJO · Owner: Luis**

`docs/SRS-tributary.md` en el árbol declara `**Versión:** 1.0` y `grep -c ADR-009` devuelve `0`. La decisión de ADR-009 (endpoint público de verificación con cuerpo restringido) llegó por instrucción de sesión, no por el documento.

No bloquea fase 0 ni fase 1: la decisión está registrada en T-404 arriba. Pero §0 del SRS es explícito — *"cualquier cambio posterior se hace por revisión versionada en §12, nunca por decisión implícita durante la implementación"*. Mientras el documento no se actualice, el repositorio tiene dos fuentes de verdad y la más autorizada es la desactualizada.

**Condición de cierre:** `docs/SRS-tributary.md` en `main` declara v1.1, contiene ADR-009, la fila de §6.5 para `GET /api/v1/records/{id}/verification`, la entrada correspondiente en §12, y la reevaluación de la etapa Reconnaissance de §7B que implica abrir una ruta sin autenticar.
