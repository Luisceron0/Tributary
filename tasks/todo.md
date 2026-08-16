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

## Fase 1 — Dominio y puertos (día 1) · bloquea todo lo demás · **COMPLETA (2026-08-15)**

`mvn test` desde la raíz: `79/79` verde, 0 dependencias de compile/runtime en `tributary-domain`, ArchUnit (CV-07 + lexemas + aislamiento de adaptadores) en verde. Cada tarea de abajo tiene su propia evidencia de ejecución real, y cada una pasó por al menos una prueba de falsabilidad (L-004) antes de darse por buena. Sigue la fase 2 (persistencia e integridad) — bloqueada hasta ahora, ya puede arrancar.

- [x] **T-100** Value objects `Money`, `TaxRate`, `Quantity` con `BigDecimal` escala 2, `HALF_UP`
  - Verificación: test de propiedades jqwik — para cualquier combinación, base + impuesto = total con escala 2
  - `mvn -pl tributary-domain test` → `Tests run: 27, Failures: 0, Errors: 0` · `BUILD SUCCESS`. 10 propiedades jqwik × 1000 casos cada una
  - TDD: los cuatro archivos de test se escribieron primero y se vieron fallar (`cannot find symbol: class Money / TaxRate`) antes de existir la implementación
  - **Prueba de falsabilidad ejecutada (L-004):** con `ROUNDING = HALF_EVEN` la suite falla — `Tests run: 27, Failures: 3` en `MoneyTest.roundsHalfUpAwayFromZero`, `MoneyTest.multiplicationRounds` y `TaxRateTest.computesTaxOnBase`. Revertido a `HALF_UP` y re-verificado en verde
  - ⚠️ **Las 10 propiedades pasaron bajo `HALF_EVEN`** (`Tests run: 10, Failures: 0`). El criterio de verificación de esta tarea, tal como está escrito, no detecta una deriva del modo de redondeo. Ver L-012
  - `Quantity` va a escala 6, no 2: no es dinero ni impuesto, y forzarla a 2 corrompería `cantidad × precio` en unidades fraccionarias. El redondeo a escala 2 ocurre una sola vez, al calcular el importe de línea
  - `Money` lleva `java.util.Currency` (JDK, no es dependencia externa) y rechaza operar entre monedas distintas con `IllegalArgumentException`
  - Sin `double` ni `float` en el módulo. Se verificará además por ArchUnit en T-105
- [x] **T-101** Modelo EN 16931: `Invoice`, `InvoiceLine`, `Buyer`, `Issuer` con términos BT/BG
  - Verificación: cada campo del modelo mapea a un BT/BG documentado en un comentario o en el README
  - `mvn -pl tributary-domain test` → `Tests run: 49, Failures: 0` · `BUILD SUCCESS`. TDD: 5 archivos de test escritos primero, vistos en rojo (`cannot find symbol`), después la implementación
  - Cada `@param` de `Issuer`, `Buyer`, `InvoiceLine`, `VatBreakdown`, `InvoiceTotals`, `Invoice` documenta su BT/BG (grep de verificación arriba). `businessKey` está documentado explícitamente como la única excepción — es ADR-003, no EN 16931
  - Añadidos `TaxCategory` (S/AE, acotado a RC-1/2/3) y `VatBreakdown` (BG-23) que el SRS no nombra pero que RF-005/CV-05 exigen para un desglose de IVA correcto por grupo
  - **El reparto del descuento de documento entre grupos de IVA (BG-23) usa reparto proporcional con el último grupo absorbiendo el residuo de redondeo**, para que la suma de importes imponibles por grupo sea siempre exacta. Decisión de diseño no pedida explícitamente por el SRS pero necesaria para que RC-2 (dos tipos de IVA + descuento de documento) dé un total correcto
  - **Prueba de falsabilidad (L-004/L-012) que encontró un hueco real en el propio test:** se desactivó la absorción de residuo (todos los grupos usan reparto proporcional). El test de RC-2 (dos grupos, 7 %/19 %) **no lo detectó** — `Tests run: 4, Failures: 0` — porque 50/110 redondea hacia arriba y 60/110 hacia abajo de forma complementaria y la suma da exacta por coincidencia. Se añadió un caso de tres grupos (5 %/10 %/15 %, importes netos iguales) donde el redondeo deriva en la misma dirección en los tres grupos; bajo el algoritmo roto da `290.01` en vez de `290.00` — **sí lo detecta**. Revertido y re-verificado en verde (49/49)
  - Este hallazgo generaliza L-012: un solo caso de ejemplo no basta para discriminar un algoritmo de reparto/redondeo; hace falta uno donde el error no pueda cancelarse por simetría
- [x] **T-102** Máquina de estados `DRAFT → SUBMITTING → ISSUED | ISSUED_WITH_WARNINGS | REJECTED | NEEDS_RECONCILIATION → MANUAL_REVIEW`
  - Verificación: test exhaustivo de transiciones; toda transición no declarada lanza excepción
  - Nota: `MANUAL_REVIEW` no tiene transición automática de salida (RF-008)
  - `mvn -pl tributary-domain test` → `Tests run: 55, Failures: 0` · `BUILD SUCCESS`. `DocumentStateTest.exhaustiveTransitionMatrix` recorre las 7×7=49 combinaciones contra una tabla esperada escrita independientemente de la implementación
  - **Ampliación sobre el diagrama compacto del SRS**, justificada por la prosa de RF-008 (no es alcance nuevo, es lo que RF-008 ya pedía sin dibujarlo): `NEEDS_RECONCILIATION → SUBMITTING` ("si no existe, se reintenta la emisión") y `NEEDS_RECONCILIATION → ISSUED/ISSUED_WITH_WARNINGS` ("si existe y está validado, se adopta el CUFE"). El conteo de "tres reconciliaciones ambiguas" que dispara `→ MANUAL_REVIEW` es responsabilidad del reconciliador (T-306, fase 3), no de esta máquina de estados — aquí solo se declara la transición como posible
  - Terminales sin transición saliente: `ISSUED`, `ISSUED_WITH_WARNINGS`, `REJECTED`, `MANUAL_REVIEW`. RF-004 corrige con un documento nuevo que referencia al original — el estado del original no cambia nunca
  - `Invoice` ahora carga `state`; `Invoice.draft()` construye en `DRAFT`, `Invoice.transitionTo(next)` valida contra `DocumentState` y devuelve una instancia nueva (nunca muta la original — coherente con la inmutabilidad de todo el sistema)
  - **Prueba de falsabilidad:** se quitó `NEEDS_RECONCILIATION → SUBMITTING` de la tabla. El test exhaustivo lo detectó en la primera combinación afectada: `canTransitionTo mismatch for NEEDS_RECONCILIATION -> SUBMITTING ==> expected: <true> but was: <false>`. Revertido y re-verificado en verde (55/55)
- [x] **T-103** Puerto `FiscalRegimePort` con `issue`, `cancel`, `query`
  - Verificación: la interfaz no menciona ningún tipo de ningún régimen concreto
  - Vive en `tributary-application` (§6.2: los puertos se definen ahí, no en el dominio), no en `tributary-domain`
  - `mvn test -pl tributary-application -am` → `Tests run: 57` (55 domain + 2 application), `Failures: 0` · `BUILD SUCCESS`
  - `FiscalRegimePortTest.mentionsNoConcreteRegimeType` verifica por reflexión que ningún tipo de retorno, parámetro o argumento genérico de `issue`/`cancel`/`query` pertenece a `com.tributary.adapter.*` — automático, no una revisión visual
  - `IssuanceResult`/`CancellationResult`/`RegimeQueryResult` usan `externalReference` (genérico) en vez de un nombre de artefacto de régimen — el mismo puerto lo implementan CO (HTTP real), ES (inserción local en cadena) y DE (validación local de XML), y los tres procesos no tienen nada en común salvo esta forma
  - **Prueba de falsabilidad:** se creó una clase de prueba en `com.tributary.adapter.co` y se añadió un método `default` a la interfaz que la devuelve. El test lo detectó con el mensaje exacto (`... references com.tributary.adapter.co.ScratchLeakProbe, a regime-specific adapter type`). Revertido y re-verificado en verde
  - Nota de proceso: para compilar `tributary-application` contra `tributary-domain` sin publicar nada, se usa el reactor de Maven (`-am`), no `mvn install`
- [x] **T-104** Reglas de negocio EN 16931 aplicables (subconjunto `BR-*` de los tres casos de referencia)
  - Verificación: un documento que viola `BR-CO-10` es rechazado en el dominio, sin llegar al adaptador
  - Alcance = el que exigen RC-1, RC-2 y RC-3, y ni una regla más
  - ⚠️ **`BR-CO-10` no tiene nada que ver con Colombia.** En EN 16931, `BR-CO-*` es la familia de reglas de *cálculo* (**CO**ndition); `BR-CO-10` es "la suma de los importes netos de línea = BT-106", que es lo que RF-005 confirma al glosarla como "(suma de bases)". La colisión con el código de país `CO` usado en `tributary-adapter-co-factus` y en "régimen CO" es accidental y peligrosa: una regla de cálculo europea leída como una regla colombiana termina implementada en el adaptador equivocado. En el código se comenta la expansión la primera vez que aparece
  - `mvn -pl tributary-domain test` → `Tests run: 64, Failures: 0` · `BUILD SUCCESS`. `EN16931BusinessRules.validate(Invoice)` acumula **todas** las violaciones (no falla en la primera), tal como exige RF-001 ("422 con la lista completa")
  - Reglas implementadas: `BR-AE-01` (línea reverse-charge exige NIF del comprador), `BR-AE-08` (línea reverse-charge exige tasa cero), `BR-AE-10` (línea reverse-charge exige motivo de exención), `BR-S-01` (línea estándar NO debe llevar motivo de exención — el caso simétrico), `BR-CO-10` (importe sin IVA = suma de netos − descuento de documento)
  - **`BR-CO-10` es honestamente tautológico en el camino actual.** `Invoice.draft()` es la única forma de construir un `Invoice`, y siempre calcula `taxExclusiveAmount` con la misma fórmula que la regla verifica — así que dentro de `validate(Invoice)` esta regla nunca puede disparar hoy. Se expuso además como función independiente (`checkNetAmountConsistency`, package-private) que toma los componentes crudos en vez de un `Invoice`, para poder alimentarla con un importe reclamado deliberadamente inconsistente — es la única manera de ejercitar la rama que RF-005 exige ("rechazado antes de serializar"). Queda como defensa en profundidad (mismo razonamiento que ADR-002 aplicado a nivel de dominio): el día que exista una segunda vía de construir un `Invoice` (ej. reconstrucción desde una fila de base de datos en fase 2), esta regla deja de ser decorativa
  - **Prueba de falsabilidad:** se invirtió la condición de `BR-AE-01` (`isPresent()` en vez de `isEmpty()`). Fallaron 3 tests, incluido `rc3HasNoViolations` — el caso positivo también vigila, no solo el negativo. Revertido y re-verificado en verde (64/64)
- [x] **T-105** Test ArchUnit: el dominio no importa Spring, Jackson, JDBC ni adaptadores — **CV-07**
  - Verificación: `mvn test -Dtest=ArchitectureTest` con 0 violaciones
  - Vive en `tributary-api/src/test/java/com/tributary/architecture/ArchitectureTest.java` (acuerdo A-2: único módulo con visibilidad de los siete)
  - **Ampliación acordada (sesión 2026-08-15).** La regla de imports no ve un campo `private String cufe`. Se añaden dos reglas más:
    - **R-lexemas:** falla si aparece `cufe`, `referenceCode`/`reference_code`, `numberingRange`/`numbering_range`, `factus`, `dian`, `aeat`, `verifactu`, `xrechnung` o `kosit` en nombres de tipo, campo o método dentro de `tributary-domain`. Cierra el hueco de L-002, que hasta ahora era una "revisión de nombres" manual sin criterio binario
    - **R-adaptadores:** falla si un adaptador importa clases de otro adaptador. Hoy nada lo verifica y es un defecto aunque compile
  - Las tres reglas se observan fallando en rojo antes de darse por buenas (L-004)
  - `mvn test -Dtest=ArchitectureTest` (comando **exacto** documentado en `.github/copilot-instructions.md`) → `Tests run: 3, Failures: 0` en los 7 módulos del reactor · `BUILD SUCCESS`. También verificado con `mvn test` (repo completo): `69/69` verde
  - **Defecto real encontrado en `copilot-instructions.md`:** el comando documentado, tal cual, fallaba con `No tests matching pattern "ArchitectureTest" were executed!` en cada módulo del reactor que no contiene esa clase (todos salvo `tributary-api`) — un comando de verificación documentado que no corre es "declarado, no verificado" por el propio estándar de §9A. Corregido con `<failIfNoSpecifiedTests>false</failIfNoSpecifiedTests>` en `maven-surefire-plugin` del POM padre — **no** toca `testFailureIgnore` (sigue en su default `false` en todos lados); solo evita que un módulo sin esa clase tumbe el build. Ver L-014
  - **R-adaptadores necesitó `.allowEmptyShould(true)`** porque los paquetes de adaptador están vacíos hasta las fases 3–5 (ArchUnit rechaza por defecto una regla de slices que no revisa ninguna clase — correctamente: una regla que siempre pasa al vacío es el mismo fallo decorativo que L-004 previene). El flag documentado en el propio test explica que solo levanta esa guarda puntual, no oculta violaciones reales una vez que existan clases
  - **Prueba de falsabilidad de las tres reglas**, con clases de sonda desechables (creadas, ejecutadas, verificado el mensaje de violación exacto, borradas):
    - Aislamiento de dominio + léxico: una clase `com.tributary.domain.ScratchArchProbe` con un campo `cufe` y un método que devuelve `java.sql.Connection` → las dos reglas fallaron a la vez, cada una con su mensaje (`field ...#cufe contains forbidden regime-specific lexeme "cufe"` y la violación de dependencia hacia `java.sql..`)
    - Aislamiento de adaptadores: dos clases sonda en `com.tributary.adapter.co` y `com.tributary.adapter.es` (compiladas dentro de `tributary-application` para no crear una dependencia real entre módulos de adaptador ni siquiera temporalmente) con una referencia cruzada → `Rule 'slices matching ... should not depend on each other' was violated`
    - Las tres reglas quedaron en verde tras revertir
- [x] **T-106** Caso de uso RF-001 con `businessKey` determinístico
  - Verificación: dos ejecuciones idénticas producen la misma clave; conteo en repositorio = 1
  - **Derivación decidida el 2026-08-15:** `businessKey = SHA-256(issuerId ‖ saleId)`, donde `saleId` es un identificador externo de la venta, **obligatorio en el request**. RF-001 dice "derivado determinísticamente de la venta" sin decir de qué campos; esto lo fija
  - Motivo del descarte del hash de contenido: dos pedidos genuinamente distintos con las mismas líneas el mismo día colapsarían en una sola clave, y el sistema se negaría a emitir la segunda factura sin forma de forzarla. La identidad de una venta la declara el llamante, no la infiere el contenido
  - Consecuencia en el contrato: `POST /api/v1/invoices` gana un campo obligatorio `saleId`. Actualizar §6.5 y el OpenAPI de T-707
  - Test negativo: un `saleId` ausente o vacío es `422`, nunca una clave generada por defecto. Un `businessKey` con un componente aleatorio o temporal rompe ADR-003 entero
  - `mvn test` (repo completo) → `Tests run: 79` (64 domain + 12 application + 3 architecture), `Failures: 0` · `BUILD SUCCESS`
  - `RegisterInvoiceUseCase` vive en `tributary-application` (orquestación transaccional, §6.2), no en el dominio. Puerto nuevo `InvoiceRepository` (`findByBusinessKey`, `save`, `countByBusinessKey` — el conteo literal que RF-001 exige). Implementación real en `tributary-persistence` es fase 2 (T-2xx); por ahora hay un `InMemoryInvoiceRepository` **de test**, bajo `src/test`, nunca empaquetado
  - `RegisterInvoiceResult` es una interfaz `sealed` con cuatro casos que calcan los flujos alternativos de RF-001 1:1 — `Created`/`AlreadyDrafted` → `201`, `Conflict` → `409`, `Invalid` → `422` con la lista completa de violaciones. Sellada a propósito: el día que la API (fase 7) haga el `switch`, el compilador exige cubrir los cuatro casos
  - **Orden invertido respecto a la prosa de RF-001, documentado en el código.** RF-001 lista "2. valida reglas de negocio · 3. calcula totales" en ese orden; la implementación construye el `Invoice` (que calcula totales) y **después** llama a `EN16931BusinessRules.validate()`, porque `BR-CO-10` necesita el total ya calculado para poder compararlo. Como `Invoice.draft()` es la única vía de construcción y siempre calcula con la misma fórmula que la regla verifica (T-104), el orden es observacionalmente idéntico para cualquier invocador: nada se persiste antes de que la validación pase, que es la garantía real que pide RF-001
  - **Prueba de falsabilidad — la más directa a un criterio literal del SRS hasta ahora:** se deshabilitó la comprobación de idempotencia (`Optional<Invoice> existing = Optional.empty()` a fuego). El test que verifica exactamente lo que RF-001 pide como criterio de aceptación (dos peticiones idénticas → un documento) lo detectó de inmediato: `expected: <AlreadyDrafted> but was: <Created>`. Revertido y re-verificado en verde (79/79)

---

## Fase 2 — Persistencia e integridad (día 2) · bloquea fases 3 y 4 · **COMPLETA (2026-08-15)**

`mvn test` desde la raíz: `104/104` verde (64 dominio + 12 aplicación + 25 persistencia + 3 arquitectura), todo contra PostgreSQL 16 real vía Testcontainers, ninguna clase en H2. Cada tarea de abajo pasó al menos una prueba de falsabilidad (L-004) antes de darse por buena — para las de mayor criticidad (T-201, T-202, T-205, T-207) se probó a mano contra un contenedor desechable con `psql` **y** después se formalizó en Java, dos pasadas independientes de la misma garantía.

- [x] **T-200** Migración Flyway: esquema de SRS §6.4
  - Verificación: `flyway migrate` sobre PostgreSQL 16 limpio, sin errores
  - `V1__initial_schema.sql`: `issuer`, `buyer`, `invoice`, `invoice_line`, `issuance_attempt`, `fiscal_record`, `audit_event`. Aplicado a mano con `psql` contra un contenedor desechable (las 5 migraciones en orden, sin error) antes de formalizarlo en `FlywayMigrator`/Testcontainers
  - **Resolución de A-4 (anotada en fase 0, resuelta aquí):** `invoice` **no** tiene columnas `cufe`/`number`. T-203 las pedía literalmente, pero son vocabulario de Factus en una tabla que comparten los tres regímenes — la misma violación de L-002 que T-105 persigue en el dominio, aplicada a SQL. La prueba de emisión vive en `issuance_attempt.external_reference` (genérico, espeja `IssuanceResult` de T-103); ver T-203 más abajo para cómo se verifica realmente la coherencia
  - `DataSourceFactory` (HikariCP) + `FlywayMigrator` en `tributary-persistence` (main); `AbstractPostgresTest` + `TestFixtures` en test, base de los 25 tests de la fase
- [x] **T-201** Trigger `BEFORE INSERT` de validación de encadenamiento en `fiscal_record`
  - Verificación: insertar un registro con `previous_hash` incorrecto es rechazado por la base de datos
  - `V2__fiscal_record_chain_validation_trigger.sql`. Verificado a mano con `psql` en 5 casos (génesis con `previous_hash` no nulo, génesis con `sequence≠1`, `previous_hash` que no matchea ningún registro, hueco de secuencia, encadenamiento correcto) — los 5 con la salida de error exacta capturada. Formalizado en `FiscalRecordChainTriggerTest`: `Tests run: 7, Failures: 0`
  - **Prueba de falsabilidad:** se comentó la comprobación de hueco de secuencia; `rejectsASequenceGap` (y solo ese) falló. Revertido, verde de nuevo
- [x] **T-202** Trigger de inmutabilidad: rechazar `UPDATE` sobre registro encadenado — **CV-02**
  - Verificación: `UPDATE fiscal_record SET payload='x' WHERE id=...;` desde `psql` devuelve `ERROR`, 0 filas afectadas
  - Evidencia: captura del error de `psql` — `ERROR: fiscal_record is immutable: UPDATE is not permitted on row ... A correction is a NEW row that references this one (RF-004), never an edit.` También probado `DELETE`, mismo resultado, datos intactos confirmados con `SELECT`
  - `V3__immutability_triggers.sql`. **Cubre también `DELETE`** y **también `audit_event`**, más allá de lo que ADR-002/CV-02 piden literalmente (solo `UPDATE`, solo `fiscal_record`) — justificado: el trigger protege contra T-001 (acceso directo a la base de datos), que un `REVOKE` a nivel de rol (T-204) no cubre si alguien se conecta con una credencial distinta; `audit_event` necesita la misma garantía de solo-apéndice por §5.3
  - Formalizado en `ImmutabilityTriggerTest`: `Tests run: 3, Failures: 0`
  - **Prueba de falsabilidad:** se comentó el trigger de `DELETE` únicamente. Solo `rejectsDelete` falló — `rejectsUpdate` y el test de `audit_event` siguieron en verde, confirmando que el test es específico y no un falso positivo genérico. Revertido
- [x] **T-203** `CHECK` de coherencia de estado: `ISSUED` implica `cufe IS NOT NULL AND number IS NOT NULL`
  - Verificación: insertar el estado imposible es rechazado
  - **No es un `CHECK` de una sola fila** (ver A-4 en T-200): la prueba real es cruzada entre tablas ("`ISSUED` implica que existe un `issuance_attempt` aceptado con `external_reference` no nulo"), y un `CHECK` de PostgreSQL no puede expresar una condición entre tablas — hace falta un trigger, coherente con el razonamiento de ADR-002. `V4__invoice_issued_state_coherence_trigger.sql`. Fija además el orden de transacción: el `issuance_attempt` debe insertarse **antes** de actualizar `invoice.state`, que es exactamente el orden que ya describe RF-002
  - Verificado a mano con `psql`: `DRAFT→ISSUED` sin prueba previa → `ERROR`; con `issuance_attempt` insertado primero → pasa
  - Formalizado en `InvoiceStateCoherenceTriggerTest`: `Tests run: 5, Failures: 0`, incluidos dos casos que un `EXISTS` ingenuo dejaría pasar: `external_reference NULL` y `outcome = REJECTED`
  - **Prueba de falsabilidad:** se quitó la condición `external_reference IS NOT NULL` del `EXISTS`. Solo `aNullExternalReferenceDoesNotSatisfyTheInvariant` falló. Revertido
- [x] **T-204** Rol de aplicación con `REVOKE UPDATE, DELETE` sobre `fiscal_record` y `audit_event`
  - Verificación: la aplicación falla al intentar un `UPDATE`, incluso con el trigger deshabilitado
  - `V5__application_roles.sql`: `tributary_app` (`NOLOGIN`) con `SELECT/INSERT/UPDATE` en tablas normales, `SELECT/INSERT` (nunca `UPDATE`/`DELETE`) en `fiscal_record`/`audit_event`. Sin contraseña en la migración — el rol real de login se aprovisiona fuera de Flyway (variables de entorno, fase 7/T-705); esto es explícito en el propio archivo, no un olvido
  - Verificado con `SET ROLE tributary_app` desde `psql` y formalizado igual en `ApplicationRoleGrantsTest`: `Tests run: 6, Failures: 0`
  - **Prueba de falsabilidad con hallazgo metodológico:** el primer intento (quitar el `REVOKE`) no cambió nada — el `REVOKE` era redundante porque el `GRANT` nunca había incluido `UPDATE`/`DELETE`, así que "quitar una negación redundante" no prueba nada. La sonda correcta es **ampliar el `GRANT`** (`GRANT ... UPDATE ...`) para demostrar que el test SÍ lo detectaría si el permiso existiera; hecho así, `applicationRoleCannotUpdateFiscalRecord` falló exactamente como debía. Revertido. Ver L-015
- [x] **T-205** Bloqueo consultivo por `chainId` para inserción serializada
  - Verificación: 20 hilos insertando en la misma cadena producen una secuencia sin huecos ni duplicados
  - `FiscalRecordRepository.append()`: `pg_advisory_xact_lock(namespace, hashtext(chainId))` (dos claves, con espacio de nombres fijo para no colisionar con un futuro uso no relacionado de locks consultivos) dentro de la misma transacción que lee la cola de la cadena e inserta. Qué huella lleva el registro nuevo **no** lo decide esta clase — es responsabilidad del adaptador ES (T-400/T-401, fase 4); esta clase solo entrega la cola actual y deja que el llamador la calcule, dentro del mismo lock
  - `FiscalRecordRepositoryConcurrencyTest`: 20 hilos con `CountDownLatch` para maximizar la contención real → secuencia exacta `1..20`, sin huecos, cadena de `previous_hash` completamente resoluble hasta un único génesis. `Tests run: 1, Failures: 0`
  - **Prueba de falsabilidad, la más reveladora de la fase:** se deshabilitó el lock. La carrera se reprodujo de forma **consistente en 3 corridas seguidas** (no esporádica) — confirma que 20 hilos reales sobre PostgreSQL real rompen la garantía de forma fiable sin el lock, no que "a veces podría pasar". Revertido y re-verificado en verde
- [x] **T-206** Verificador de cadena RF-006 con usuario de base de datos de solo lectura
  - Verificación: 1.000 registros sanos → `INTACT` en < 2 s
  - `ChainVerifier`: recorre la cadena, recalcula cada huella y compara con la persistida, reporta el primer registro roto con su predecesor y ambas huellas, **continúa para cuantificar el alcance** (cuenta el total de discrepancias, no solo la primera). El algoritmo real de huella (T-400/T-401) todavía no existe (fase 4) — `ChainVerifier` lo recibe como dependencia inyectada en vez de asumir uno, así que fase 2 prueba la mecánica de verificación, no un algoritmo concreto
  - Rol de solo lectura (`tributary_verifier`) verificado en `ApplicationRoleGrantsTest`: puede `SELECT`, no puede `INSERT` ni `UPDATE`
  - Rendimiento medido real: `ChainVerifier.verify() on 1000 records: 11ms (budget 2000ms)` — **~180× más rápido** que el presupuesto. `ChainVerifierTest.performanceOnAThousandRecords`: `Tests run: 1, Failures: 0`
- [x] **T-207** Test de detección de manipulación — **CV-03**
  - Verificación: con el trigger deshabilitado, alterar un registro intermedio hace que el verificador devuelva `BROKEN` señalando ese registro exacto
  - Evidencia: `ChainVerifierTest.tamperDetection` — cadena de 3 registros, se deshabilita el trigger de `UPDATE`, se altera el `canonical_payload` del **segundo**, se reactiva el trigger, se verifica: `brokenRecordId` = el segundo registro exacto (no el primero ni el tercero), `predecessorId` = el primero, `totalMismatches` = 1, `recordsVerified` = 3. `Tests run: 3, Failures: 0` (incluye este caso). **Esta es la captura principal del portafolio**
  - **Prueba de falsabilidad — la más importante de toda la fase 2:** se invirtió la comparación del verificador (`recomputed.equals(hash)` en vez de `!recomputed.equals(hash)`). Los 3 tests fallaron, y específicamente `tamperDetection` falló porque con la comparación invertida un registro **manipulado se reporta como `Intact`** — el falso negativo que §9A señala como "peor que no tener el control, porque genera confianza injustificada". Revertido y re-verificado en verde
- [x] **T-208** Tests con Testcontainers para todo lo anterior
  - Verificación: la suite corre contra PostgreSQL real, no H2
  - `grep -ri h2database` sobre `pom.xml` y el árbol de dependencias → sin resultados. Los 25 tests de `tributary-persistence` extienden `AbstractPostgresTest` (contenedor `postgres:16` real, `@Testcontainers`, migración real vía `FlywayMigrator`). No es una tarea aparte con su propio código — es la consecuencia de cómo se construyó todo lo anterior desde T-201
  - **Defecto de entorno encontrado y corregido, no relacionado con SQL/dominio:** Testcontainers 1.21.3 no podía conectar con el daemon Docker de este entorno (`client version 1.32 too old, minimum 1.40`) — incompatibilidad entre la versión de `docker-java` empaquetada y un daemon Docker muy reciente (29.3.0). Corregido subiendo a `testcontainers 1.21.4`. Ver L-016
  - **Segundo defecto de entorno, independiente:** `HikariCP` trae `slf4j-api:1.7.36` transitivamente, que gana por cercanía sobre la versión `2.0.17` que ya usaban `archunit`/`testcontainers`, rompiendo el binding de logging silenciosamente (por eso el primer defecto fue tan difícil de diagnosticar — los logs de diagnóstico de Testcontainers estaban siendo tragados). Corregido fijando `slf4j-api:2.0.17` explícito en el POM. Ver L-016

---

## Fase 3 — Adaptador CO / Factus (día 3) · paralelizable con fase 4 · **T-300–T-309 completas (2026-08-15)**

**Credenciales de sandbox recibidas del usuario 2026-08-15, nunca hardcodeadas.** Van en `.env` (gitignorado desde T-001) con nombres `FACTUS_SANDBOX_*`; `.env.example` (commiteado) documenta la forma sin valores. Verificado con un canario real: forzar `git add -f .env` y commitear da `EXIT CODE: 1` — el hook las protege igual que a cualquier otro secreto.

**Investigación de la API real, antes de escribir el adaptador:** la documentación de `developers.factus.com.co` bloquea `WebFetch` (403), así que el esquema real se obtuvo en dos vías independientes que coincidieron: (a) sondeo directo contra el sandbox con las credenciales reales — autenticación real (`POST /oauth/token`, `HTTP_STATUS=200`), `GET /v2/numbering-ranges` (rango real `id=389`, "Factura de Venta"), y una validación de factura real completa con CUFE real devuelto; (b) un artefacto de documentación oficial de Factus que el usuario aportó directamente, que confirmó campo por campo lo ya sondeado. **Confirmación empírica notable:** la llamada real devolvió `is_validated: true` con `errors` no vacío (tres notificaciones DIAN tipo RUT01/FAJ44b/FAJ43b) — exactamente el escenario que RF-002 describe como `ISSUED_WITH_WARNINGS`, observado en producción del sandbox antes de escribir una sola línea de T-305.

- [x] **T-300** Cliente OAuth2 con refresh de vuelo único
  - Verificación: 20 hilos con token expirado disparan exactamente una petición de refresh
  - `FactusOAuth2Client` (bloqueo de doble verificación, lógica pura sin HTTP) + `FactusAuthGateway` (HTTP real vía `java.net.http.HttpClient`, sin Spring — §6.2 solo asigna Spring Boot a `tributary-api`) + `FactusToken`/`FactusCredentials`. `Tests run: 7, Failures: 0` (3 del cliente + 4 del gateway contra WireMock)
  - Forma de request/response confirmada en vivo: `POST /oauth/token` con `{grant_type:"password", client_id, client_secret, username, password}` → `{token_type:"Bearer", expires_in:3600, access_token, refresh_token}`. WireMock reproduce exactamente esta forma, no una inventada
  - `client_secret` y `password` nunca aparecen en un mensaje de excepción — verificado con test dedicado, no solo declarado
  - **Prueba de falsabilidad 1 (single-flight):** se quitó el `synchronized` del cliente. El test de 20 hilos detectó `20` llamadas de refresh en las 3 corridas, de forma consistente (no esporádica). Revertido
  - **Prueba de falsabilidad 2 (parseo):** se ignoró `expires_in` del gateway (fijado a 1s). `parsesTheTokenResponse` lo detectó. Revertido
  - Nota de proceso: `WireMockExtension` con el DSL estático (`stubFor`/`verify` importados estáticamente) apunta a un cliente global que asume el puerto 8080 salvo `WireMock.configureFor(...)`; con puerto dinámico hay que usar los métodos de instancia (`wireMock.stubFor(...)`). Encontrado como fallo real (`Connection refused: localhost:8080`) y corregido
- [x] **T-301** Limitador saliente a 60 req/min con ventana deslizante
  - Verificación: 200 peticiones seguidas sin un solo 429; ninguna ventana de 60 s supera 60 peticiones
  - `FactusRateLimiter.reserveSlot(Instant)` es la función de decisión pura (dado "ahora", ¿cuándo puedo proceder?), separada de `acquire()` que sí duerme — permite probar 200+ peticiones sin esperar minutos reales. Ventana deslizante real, no un balde de tamaño fijo que resetea en el tick del reloj
  - `Tests run: 4, Failures: 0`, incluido el criterio literal: 200 peticiones simultáneas → ninguna ventana de 60s con más de 60 permisos
  - **Prueba de falsabilidad:** error de límite (`<=` en vez de `<`, permite 61). Detectado por 2 tests, incluido el que verifica el criterio literal. Revertido
- [x] **T-302** Manejo de `429`: respetar `Retry-After` con jitter, registrar como incidente
  - Verificación: forzado con WireMock, el reintento ocurre después del tiempo indicado
  - Implementado dentro de `FactusBillGateway` (junto a T-305, comparten el mismo ciclo de request/respuesta): un `429` nunca se trata como ninguno de los cuatro resultados normales; se espera `Retry-After` (forma delay-seconds) + jitter aleatorio (0–500 ms), se reintenta **una vez**, y si el reintento también da `429` el resultado final es `UNREACHABLE` — nunca se reintenta indefinidamente
  - `retriesAfter429`: escenario WireMock con estado (`429` con `Retry-After: 1` → éxito), tiempo real transcurrido medido → `≥ 1s`, exactamente 2 peticiones verificadas. `Tests run: 5, Failures: 0`
- [x] **T-303** Traducción del dominio al payload de Factus
  - Verificación: el JSON generado valida contra el ejemplo de referencia campo a campo
  - `FactusPayloadMapper` con Jackson. `Tests run: 8, Failures: 0` contra el esquema confirmado en vivo
  - Decisiones documentadas en el propio código (varias veces sin equivalente en el dominio, inevitable — ADR-001 prohíbe el vocabulario de régimen HACIA el dominio, no al revés): `payment_details` por defecto a un único pago en efectivo por el total exacto (el dominio no modela forma de pago, §3 lo excluye); `unit_measure_code` traduce solo los códigos UN/ECE que RC-1/2/3 usan (`C62`→`94`), cualquier otro falla fuerte en vez de adivinar; `standard_code` fijo en `"999"` ("adopción del contribuyente", la designación de Factus para "sin clasificación específica"); comprador con NIF → persona jurídica (tesis B2B del proyecto), sin NIF → el "consumidor final" confirmado empíricamente contra el sandbox real; línea reverse-charge (RC-3) → `is_excluded:true` al 0 % (DIAN no tiene un mecanismo de inversión del sujeto pasivo estilo UE; el texto del motivo de exención EN 16931 no tiene dónde ir en el esquema de Factus y se descarta, simplificación de alcance declarada, no un olvido)
  - **Verificación adicional en el sandbox real, más allá de lo pedido:** se reconstruyó a mano el JSON exacto que produce `FactusPayloadMapper` para una `Invoice` estilo RC-1 con comprador sin NIF, y se envió contra `/v2/bills/validate` real → `status: Created`, `is_validated: True`, número y CUFE reales asignados, mismas notificaciones DIAN de la sonda anterior. La forma que el mapeador produce **es** la que el sandbox acepta, no una suposición
- [x] **T-304** Caso de uso RF-002 con transición confirmada antes de la E/S de red
  - Verificación: el estado `SUBMITTING` es visible desde otra conexión antes de que la petición salga
  - Requirió construir primero lo que la fase 2 dejó pendiente porque nada lo necesitaba todavía: `JdbcInvoiceRepository` (implementación real del puerto de T-106; solo existía un doble en memoria) e `IssuanceAttemptPort`/`JdbcIssuanceAttemptRepository` (tabla `issuance_attempt` sin puerto desde fase 2)
  - `IssueInvoiceUseCase`: precondición estricta `DRAFT` (RF-002 literal). Un documento en `SUBMITTING` por un crash anterior **no** se reintenta aquí — eso es trabajo del reconciliador (RF-008, T-306), que consulta antes de decidir. `issuance_attempt` se graba **antes** de transicionar a `ISSUED`/`ISSUED_WITH_WARNINGS`, en el orden exacto que el trigger de T-203 exige
  - **Verificación con una conexión JDBC separada de verdad**, no una aserción indirecta: el `FiscalRegimePort` falso, dentro de su propio `issue()`, abre su propio `JdbcClient` contra el mismo `DataSource` y lee `invoice.state` — ahí mismo, antes de devolver una respuesta. `Tests run: 2, Failures: 0`
  - **Prueba de falsabilidad:** se invirtió el orden (llamar `issue()` antes de guardar `SUBMITTING`). El test lo detectó con precisión: `expected: <SUBMITTING> but was: <DRAFT>`. Revertido
- [x] **T-305** Manejo de los cuatro resultados: `201` limpio, `201` con `errors`, `is_validated=false`, timeout
  - Verificación: contract tests con WireMock para los cuatro; `errors` se preserva íntegro
  - `FactusBillGateway.validate()` devuelve directamente `IssuanceResult` (el tipo de T-103), sin inventar un tipo paralelo. Los cuatro casos formalizados con WireMock, con las formas de respuesta confirmadas en vivo esta sesión: limpio → `ACCEPTED`; `is_validated=true` con `errors` no vacío → `ACCEPTED_WITH_WARNINGS` (con las **dos** advertencias preservadas íntegras, no solo la primera); `is_validated=false` → `REJECTED`; fallo de conexión (`Fault.CONNECTION_RESET_BY_PEER`) → `UNREACHABLE`, nunca confundido con `REJECTED`
  - `data.errors` es un **objeto** clave→mensaje por regla DIAN (`{"RUT01": "...", "FAJ44b": "..."}`), no un array — confirmado en vivo, no asumido
  - **Prueba de falsabilidad:** se colapsó la distinción `ACCEPTED`/`ACCEPTED_WITH_WARNINGS` (cualquier `is_validated=true` → `ACCEPTED`). `acceptedWithWarnings` lo detectó — es exactamente el escenario que confirmé en el sandbox real antes de escribir el test. Revertido
- [x] **T-306** Reconciliador RF-008: consulta obligatoria antes de reintentar
  - Verificación: test con mock que afirma el **orden** de llamadas — ninguna emisión sin consulta previa
  - `ReconcileInvoiceUseCase`: `FiscalRegimePort.query()` siempre antes que `issue()`. Doble falso hecho a mano (registra el orden de llamadas) en vez de Mockito — no hacía falta una librería nueva para una sola aserción de orden
  - `Tests run: 7, Failures: 0`: el orden se verifica para los cuatro desenlaces de `QueryOutcome`, incluido que `FOUND_VALIDATED`/`FOUND_REJECTED`/`AMBIGUOUS` **nunca** llaman a `issue()` en absoluto (no es solo "el orden es correcto cuando se llama", es "a veces no se llama nunca")
  - **Hueco real encontrado en T-102 (ya commiteada) al escribir el test:** `NEEDS_RECONCILIATION → REJECTED` no estaba en la tabla de transiciones. RF-008 nunca narra qué pasa si la reconciliación encuentra un documento rechazado — solo describe encontrado+validado, no-encontrado y ambiguo. Es un desenlace real y alcanzable que el SRS simplemente no enumeró. Agregado a `DocumentState` y a la tabla independiente de `DocumentStateTest`, las dos consistentes, `Tests run: 4, Failures: 0`
  - Tercera ambigüedad consecutiva → `MANUAL_REVIEW`: el conteo de intentos ambiguos consecutivos **no tiene columna en el esquema** (no pedida por ninguna tarea) — se modela como parámetro explícito de entrada/salida del caso de uso (`consecutiveAmbiguousSoFar` → `Ambiguous.consecutiveAmbiguousCount()`), dejando la persistencia de ese contador como responsabilidad del llamador. Simplificación de alcance declarada
  - **Prueba de falsabilidad:** se insertó una llamada a `issue()` antes de `query()`. Detectado por 5 de los 7 tests, incluido el que verifica el criterio literal. Revertido
- [x] **T-307** Test de caos — **CV-10**, contra el sandbox real (no WireMock)
  - Verificación: emitir de verdad, perder el estado local (simulando el crash justo después de que Factus respondió), reconciliar, y consultar dos veces por `reference_code` en Factus → exactamente 1 documento, consistente
  - `FactusChaosLiveSandboxTest`, deliberadamente fuera del `mvn test` por defecto (necesita credenciales reales, consume cuota real del sandbox) — se corre explícito con `-Dtest=FactusChaosLiveSandboxTest -DfactusLiveSandbox=true`. Por qué "matar el proceso" se modela así y no con un `SIGKILL` real: sincronizar una señal justo en el instante "Factus ya confirmó, el proceso local todavía no lo registró" no tiene un hook confiable; lo que CV-10 necesita probar es el efecto posterior — dado un documento que Factus ya tiene pero que el sistema local no sabe que tiene, ¿la recuperación evita reemitir? — y eso se reproduce de forma determinista reseteando la fila local a `NEEDS_RECONCILIATION` tras una emisión real
  - **Evidencia real, no simulada:** `businessKey=chaos-37bc1ecf-4959-4241-9c1e-d136dce01d36 cufe=3273f1e51872d30eb558b1cf221076e43beb3c48eb696326f558c01be5c0449fd454338ad17cd65ccd3c274e40138ff8 number=SETP990015253` — `Tests run: 1, Failures: 0`. Dos consultas consecutivas resuelven al mismo `number`, nunca `AMBIGUOUS`
  - **Dos bugs reales encontrados al correr contra el sandbox real, ninguno detectable con WireMock porque WireMock solo repite formas ya asumidas correctas** (ver `lessons.md` L-019/L-020):
    1. El validador real rechaza `customer` sin `names` incluso con `legal_organization_code=1`, contradiciendo la lectura literal del artefacto de documentación oficial ("obligatorio solo si =2"). `FactusPayloadMapper.customer()` corregido para enviar `names` siempre
    2. `GET /v2/bills` nunca expone `cufe` en sus elementos (confirmado con `curl` directo; variantes de endpoint de detalle probadas, las tres 404) — solo `number`. `FactusQueryGateway` corregido para adoptar `number`, documentado en su Javadoc de clase como desviación deliberada de la redacción literal de RF-008 ("se adopta el CUFE"); el CUFE fiscal ya quedó persistido en `issuance_attempt` desde la emisión original (T-304)
  - **Prueba de falsabilidad, dos sondas, una por cada corrección de esta tarea:** (a) revertido `FactusQueryGateway` a extraer `cufe` en vez de `number` → `FactusQueryGatewayContractTest.foundValidated` lo detectó (`NoSuchElementException`); (b) removida la línea que envía `names` incondicionalmente en `FactusPayloadMapper` → `FactusPayloadMapperTest.rc1BuyerWithTaxIdIsACompany` lo detectó (`NullPointerException` sobre el campo ausente). Ambas revertidas, suite completa del módulo verde de nuevo
  - **Verificación de regresión, repo completo:** `mvn test` desde la raíz → `BUILD SUCCESS`, sin fallos, `FactusChaosLiveSandboxTest` correctamente saltado por defecto (`Skipped: 1`)
- [x] **T-308** Test de concurrencia: 20 hilos sobre el mismo documento → 1 emisión
  - **Bug real encontrado al diseñar el test, antes de escribirlo:** el `IssueInvoiceUseCase` de T-304 (ya commiteada) hacía leer-y-luego-guardar (`findByBusinessKey` → `transitionTo` → `save`), que bajo concurrencia real deja que los 20 hilos lean `DRAFT` antes de que ninguno confirme `SUBMITTING` — los 20 llamarían a Factus
  - **Corrección: `InvoiceRepository` gana `tryTransition(businessKey, from, to): boolean`**, un `UPDATE ... WHERE business_key = ? AND state = ?` atómico que solo un hilo puede ganar (PostgreSQL serializa los `UPDATE` concurrentes sobre la misma fila). `IssueInvoiceUseCase` reescrito para usarlo en el paso crítico. Los tres implementadores del puerto actualizados (`JdbcInvoiceRepository` real, y los dos dobles en memoria de T-106/T-306)
  - `IssueInvoiceConcurrencyTest`: 20 hilos reales, Postgres real, `CountDownLatch` para maximizar contención. `Tests run: 1, Failures: 0` → exactamente 1 llamada a Factus, exactamente 1 `Issued`, exactamente 19 `InvalidState`, exactamente 1 documento en `ISSUED` al final
  - **Prueba de falsabilidad, la más contundente de la fase 3:** se revirtió a leer-luego-guardar. Dos corridas → `18` y `9` llamadas duplicadas a Factus respectivamente (no determinístico, la firma exacta de una carrera real bajo scheduling de SO, no un número fijo). Confirma que el bug era real y que la corrección lo resuelve de raíz. Revertido
- [x] **T-309** Guarda de entorno fail-closed
  - Verificación: el servicio se niega a arrancar contra la URL de producción sin la variable de habilitación explícita
  - `FactusEnvironment`: `resolve()` (sandbox, camino normal) vs `resolveProduction()` (exige `FACTUS_ENABLE_PRODUCTION=true`, si no `IllegalStateException`). Nombres de variable de producción (`FACTUS_PRODUCTION_*`) y sandbox (`FACTUS_SANDBOX_*`) completamente distintos — no hay forma de confundirlos por copy-paste. `resolve()` además **rechaza activamente** si la URL de sandbox configurada parece apuntar a producción (defensa contra un error de configuración, no solo el camino feliz)
  - `Tests run: 5, Failures: 0`. Toma `Function<String,String>` en vez de leer `System.getenv()` directamente — testeable con un `Map` simple, sin depender de variables de entorno reales
  - **Prueba de falsabilidad:** se quitó la comprobación de `FACTUS_ENABLE_PRODUCTION`. `refusesProductionWithoutExplicitEnablement` lo detectó — es el caso más crítico de los cinco. Revertido
  - **También construido, no listado como tarea propia pero necesario para T-307/T-308:** `FactusQueryGateway` (`GET /v2/bills?filter[reference_code]=...`, forma confirmada en vivo esta sesión) y `FactusFiscalRegimeAdapter` (implementación real de `FiscalRegimePort` que une OAuth2 + limitador + payload mapper + query). `cancel()` (notas crédito, RF-004) queda sin implementar a propósito — fuera del alcance de T-3xx — y falla fuerte (`UnsupportedOperationException`) en vez de simular un no-op silencioso. Un fallo de red/servidor en la consulta mapea a `AMBIGUOUS`, **nunca** a `NOT_FOUND` — confundirlos dejaría al reconciliador reintentar una emisión tras un error de red que nunca llegó a Factus, el mismo riesgo de duplicado que ADR-003 existe para prevenir. `Tests run: 35, Failures: 0` en todo el módulo

---

## Fase 4 — Adaptador ES / Verifactu (día 4) · paralelizable con fase 3 · **T-400–403 completas (2026-08-15), T-404 parcial, T-405 bloqueada**

`mvn test` desde la raíz: `124/124` verde. T-400/401/402/403 completas y verificadas con la misma disciplina de falsabilidad que fases 1–2. T-404 tiene su capa de aplicación lista; su capa HTTP y toda T-405 dependen de que `tributary-api` tenga un servidor REST real, que es literalmente la tarea de fase 7 — no se adelantó esa infraestructura para no violar la regla de "no agregues infraestructura que ninguna tarea pida todavía" en sentido inverso.

- [x] **T-400** Canonicalización de campos del registro de alta según RD 1007/2023
  - Verificación: la huella es reproducible desde los datos persistidos
  - **Resolución de L-011 (campo pendiente sin definir en el SRS):** forma canónica = texto plano con orden de campo fijo y separadores explícitos (`CLAVE=valor|CLAVE=valor|...`), **nunca JSON** — decisión directa de L-017 (JSONB reformatea; hasta una librería JSON puede variar el orden de claves entre versiones). Campos mínimos elegidos: NIF emisor, `businessKey`, fecha de emisión, NIF comprador (vacío explícito si no aplica), moneda, base imponible, cuota, total, marca de tiempo de generación
  - `VerifactuHasherTest.canonicalizationProducesTheExactExpectedString` fija un **literal exacto**, no una comparación contra sí mismo — cierra el hueco que L-012/L-017 señalan (una propiedad de "da lo mismo dos veces" no discrimina un formato consistente pero equivocado)
- [x] **T-401** Cálculo de huella SHA-256 incorporando la huella previa
  - `mvn test -pl tributary-adapter-es-verifactu -am` → `Tests run: 8, Failures: 0` (más 76 heredados de dominio/aplicación)
  - `hashMatchesAKnownLiteralValue` fija el resultado contra un SHA-256 calculado **fuera** del código bajo prueba (`printf '...' | sha256sum`), no derivado de él
  - **Prueba de falsabilidad:** se invirtió el orden de concatenación (`previousHash + canonicalFields` en vez de al revés). De los 8 tests, **solo el que fija el literal independiente falló** — los demás (determinismo, forma hexadecimal, "incorpora la huella previa") son todos relativos y no habrían detectado un algoritmo consistente pero equivocado. Confirma otra vez L-012: sin un valor de referencia externo, ningún test relativo prueba el algoritmo en sí. Revertido
- [x] **T-402** Registro de anulación vinculado al de alta (RF-004)
  - Verificación: tras anular, el verificador sigue devolviendo `INTACT`
  - Canonicalización unitaria (`VerifactuHasher.canonicalizeAnulacion`) en `tributary-adapter-es-verifactu`: referencia la huella del registro de alta, motivo, marca de tiempo. `Tests run: 10, Failures: 0` (con literal fijado, mismo criterio que T-400/401)
  - **El criterio literal ("tras anular, el verificador sigue INTACT") cruza dos módulos** que no pueden depender entre sí (adaptadores solo dependen de `application`; `persistence` también) — se prueba en `tributary-api`, el único módulo que ve ambos a la vez (mismo motivo que aloja `ArchitectureTest`, acuerdo A-2). Se agregó Testcontainers a `tributary-api` (antes solo lo tenía `tributary-persistence`; el scope `test` no es transitivo)
  - `VerifactuChainIntegrationTest.chainStaysIntactAfterCancellation`: registro de alta real (`FiscalRecordRepository.append`) + registro de anulación real referenciando su huella, misma cadena → `ChainVerifier.verify()` = `Intact`, `recordsVerified=2`. `Tests run: 1, Failures: 0`
  - **Prueba de falsabilidad, más reveladora de lo esperado:** se simuló "olvidar encadenar" calculando la huella de la anulación con `previousHash=Optional.empty()` en vez de la cola real. El trigger de la cadena (T-201) **no** lo detectó — el `previous_hash` que persiste el repositorio siempre viene de la cola real que él mismo leyó, no de la sonda — pero el verificador sí, con `Broken`, porque la huella persistida ya no coincidía con la recalculable desde `canonical_payload + previous_hash`. Confirma ADR-002 de punta a punta: aunque la contabilidad de la cadena esté bien, un cálculo de huella equivocado del llamador igual queda expuesto. Revertido
- [x] **T-403** Generación del QR apuntando al verificador propio — **CV-12** (ADR-007)
  - Verificación: test que falla si aparece cualquier host de la AEAT en el contenido del QR
  - Leyenda de modo no remitido presente
  - `VerifactuQrGenerator` en `tributary-adapter-es-verifactu`, con `com.google.zxing:core`/`javase` 3.5.4 (dependencia nueva, justificada por esta tarea). `Tests run: 6, Failures: 0`
  - **CV-12 se aplica en dos capas, no solo en el test:** el generador **rechaza en tiempo de ejecución** cualquier `verifierBaseUrl` que contenga un host conocido de la AEAT (`IllegalArgumentException`), no solo se confía en que el test lo detecte después. La garantía vive en el código, no únicamente en la suite que lo vigila
  - `decodedPngMatchesContentAndCarriesNoAeatHost` decodifica el PNG generado con un lector QR real (ZXing `MultiFormatReader`) y revisa el contenido decodificado — la forma más fuerte de "no hay host de la AEAT en el QR": sobre la imagen que un inspector realmente escanearía, no sobre la cadena de origen antes de codificar
  - **Prueba de falsabilidad:** se quitó la lista de rechazo de hosts AEAT. Solo `refusesAnAeatBaseUrl` falló — confirma que ese test y `noAeatHostAnywhereInContent` cubren preocupaciones distintas y complementarias (rechazo activo vs. ausencia accidental), no una duplicada. Revertido
- [~] **T-404** Endpoint `GET /api/v1/records/{id}/verification` — **parcial, capa HTTP bloqueada hasta fase 7**
  - Verificación: devuelve registro, huella, posición en cadena y declaración de modo no remitido
  - **Resuelto por ADR-009 (SRS v1.1), sin decisión pendiente.** Es la **única ruta pública sin autenticación** de todo el sistema. Cuerpo restringido a `{recordId, hash, previousHash, chainPosition, issuedAt, nonSubmittedNotice}` — sin PII, sin importes, sin identificadores fiscales
  - Test negativo obligatorio: cualquier campo fuera de esos seis en la respuesta hace fallar el test. Un endpoint público que crece por conveniencia es una fuga de PII con revisión previa aprobada
  - ⚠️ Ver bloqueante B-02: `docs/SRS-tributary.md` en el árbol de trabajo sigue en v1.0 y no contiene ADR-009 ni la fila de §6.5
  - **Hecho:** `GetRecordVerificationUseCase` + `RecordVerificationView` en `tributary-application`, armando exactamente los seis campos de ADR-009 a partir de `FiscalRecordPort.findById` (nuevo método en el puerto, implementado en `FiscalRecordRepository`). `Tests run: 3, Failures: 0`. Prueba de falsabilidad (intercambiar `hash`/`previousHash` en el mapeo) detectada por 2 de los 3 tests; revertido
  - **No hecho, bloqueado a propósito:** el `@RestController` real. §6.2 asigna Spring Boot específicamente a `tributary-api`, y ninguna fase anterior metió ese framework antes de que una tarea lo pidiera — T-404 es la primera que lo pide, y es literalmente la tarea de la fase 7. Traerlo ahora habría sido "agregar infraestructura que ninguna tarea de esta fase pide" en la dirección contraria (adelantarla en vez de omitirla). Queda para fase 7, con la capa de aplicación ya lista para que el controlador sea una envoltura delgada
- [!] **T-405** Test negativo: no existe ninguna ruta de API que modifique un documento emitido — **bloqueado, no parcialmente ejecutable**
  - Verificación: barrido de todos los endpoints; ningún `PUT`/`PATCH` sobre facturas o registros
  - A diferencia de T-404, esta tarea **no tiene versión parcial posible**: su verificación es barrer endpoints HTTP reales, y hoy no existe ninguno. Bloqueada íntegramente hasta que la fase 7 levante `tributary-api` con Spring Boot

---

## Fase 5 — Adaptador DE / XRechnung (día 5) · paralelizable con fases 3 y 4

- [x] **T-500** `SecureXmlFactory`: única vía de creación de parsers
  - DTD off, entidades externas off, `FEATURE_SECURE_PROCESSING` on, límites de tamaño y profundidad
  - **Probado empíricamente contra el JDK real de este entorno (25.0.2) antes de escribir una sola línea**, no contra documentación: un `DocumentBuilderFactory` sin configurar filtra de verdad el contenido de `/etc/passwd` al DOM (XXE real, no teórico, en este entorno); ese mismo JDK sin configurar además rechaza ya por su cuenta más de 100 niveles de anidación y más de 2500 expansiones de entidad — límites de fábrica de una versión reciente de JAXP, no garantizados en cualquier JDK
  - `disallow-doctype-decl=true` es la defensa primaria (sin DOCTYPE no hay declaración de entidad posible, externa o interna — cierra XXE y "billion laughs" en un solo golpe); `FEATURE_SECURE_PROCESSING`, `external-general/parameter-entities=false` y `setXIncludeAware(false)` quedan como defensa en profundidad explícita, no implícita (§5.3: "sin configuración por defecto heredada de la librería")
  - `MAX_ELEMENT_DEPTH=64`, elegido deliberadamente **por debajo** del default de 100 de este JDK (ver L-021) para que el límite propio sea demostrablemente el que actúa, no una coincidencia con la plataforma. `MAX_INPUT_BYTES=5 MiB` vía un `InputStream` que cuenta bytes y falla antes de completar el búfer, no una comprobación posterior a cargar todo en memoria
  - `mvn test -pl tributary-adapter-de-en16931` → `Tests run: 7, Failures: 0`. TDD: el test se escribió primero, visto en rojo (`cannot find symbol: SecureXmlFactory`)
  - **Bug real encontrado por el propio test, antes de cualquier commit:** el límite de tamaño rechazaba un documento de exactamente `MAX_INPUT_BYTES` (el guard usaba `>=` en vez de `>`, cortando un byte antes de confirmar que realmente sobraba). Corregido: se permite un byte de más para distinguir "exactamente en el límite, luego EOF" de "excede el límite"
  - **Prueba de falsabilidad (la que importa, ver L-021):** se quitó la línea `setAttribute(maxElementDepth, 64)`. El test de profundidad (74 niveles, sin entidades) pasó de rojo a verde — el JDK seguía bloqueando por su propio default de 100, así que un documento de 74 niveles ya no lo alcanzaba. Revertido y re-verificado en verde (7/7)
- [x] **T-501** Regla ArchUnit: prohibido instanciar parsers XML fuera de la factoría
  - Verificación: la regla falla en rojo cuando se introduce deliberadamente una instanciación directa
  - `noClasses().that().doNotHaveFullyQualifiedName(SecureXmlFactory)...should().callMethod(...)` sobre `DocumentBuilderFactory`/`SAXParserFactory`/`XMLInputFactory` — las tres formas estándar de crear un parser DOM/SAX/StAX en Java. `TransformerFactory`/`SchemaFactory` deliberadamente fuera: nada en el módulo los usa hoy (la validación XSD/Schematron se delega entera al subproceso KoSIT externo, T-504), incluirlos habría sido regla decorativa sin nada que vigilar todavía
  - `mvn test -pl tributary-api -Dtest=ArchitectureTest` → `Tests run: 4, Failures: 0` (3 previas de T-105 + esta)
  - **Prueba de falsabilidad:** se añadió `ScratchParserLeakProbe` (clase descartable en `com.tributary.adapter.de`, fuera de `SecureXmlFactory`) con una llamada directa a `DocumentBuilderFactory.newInstance()`. La regla lo detectó de inmediato (`Architecture Violation ... was violated (1 times)`). Eliminada la clase y re-verificado en verde (4/4)
- [x] **T-502** Sonda XXE — **CV-04**
  - Verificación: documento con entidad externa apuntando a `file:///etc/passwd` → excepción de parseo, sin lectura de archivo, sin conexión saliente observada
  - Evidencia: log del parser + captura de tráfico vacía
  - `XxeProbeTest`, el artefacto de evidencia autocontenido de CV-04: la mitad de lectura de archivo repite la cobertura de `SecureXmlFactoryTest` (T-500) a propósito, para que este archivo solo baste como evidencia; la mitad de conexión saliente es nueva — SSRF vía entidad XXE usa el mismo mecanismo DOCTYPE pero con esquema `http://` en vez de `file://`, y nada de T-500 había probado antes cero conexiones salientes específicamente
  - **La prueba de "sin conexión saliente" usa un `ServerSocket` real en loopback**, no una inferencia: un hilo bloqueado en `accept()` que solo se libera si (a) llega una conexión real (fuga) o (b) el propio test cierra el socket (prueba de que nunca llegó ninguna) — sin `sleep`, sin condición de carrera en ningún sentido
  - `mvn test -pl tributary-adapter-de-en16931 -Dtest=XxeProbeTest` → `Tests run: 2, Failures: 0`. Evidencia real impresa: `SAXParseException: DOCTYPE is disallowed...` en ambos casos, `connectionReceived=false` en el de conexión
  - **Prueba de falsabilidad, la que de verdad importa aquí:** no alcanza con probar que `SecureXmlFactory` es segura (ya probado en T-500) — hacía falta probar que el propio detector (el `ServerSocket`) es capaz de observar una fuga real si la hubiera. Se lanzó la misma carga maliciosa contra una factoría **sin endurecer**, en un hilo daemon (una factoría sin endurecer, al usar el esquema `http://`, se queda colgada indefinidamente esperando una respuesta HTTP que este listener nunca envía — irrelevante para lo que la sonda mide, y un hilo daemon no puede bloquear el cierre de la JVM). El detector sí observó la conexión real (`connectionReceived=true`), confirmando que el mecanismo de prueba funciona de verdad y no es un falso negativo estructural. Revertido a llamar `SecureXmlFactory.parse(...)` y re-verificado en verde (2/2)
- [x] **T-503** Mapeo del dominio a CII / XRechnung
  - `CiiInvoiceMapper` con JAXB (SRS 6.2 fija esta tecnología para el módulo: `jakarta.xml.bind-api:4.0.2` + `jaxb-runtime:4.0.6`, el namespace moderno Jakarta EE 10 — `javax.xml.bind` salió del JDK tras la 11)
  - **Forma, namespaces y orden de elementos confirmados contra instancias reales, ya validadas por KoSIT, de `itplr-kosit/xrechnung-testsuite`** (no contra la prosa del estándar sola): el caso de negocio `01.01a` (variante CII/uncefact) dio la estructura base; una búsqueda entre 20 casos más encontró el `01.04a` (categoría exenta `O`) con `ExemptionReason` real, confijando el orden exacto a nivel de documento: `CalculatedAmount, TypeCode, ExemptionReason, BasisAmount, CategoryCode, RateApplicablePercent`. Ninguna muestra de la suite ejerce `ExemptionReason` a nivel de línea — ese orden queda como mejor esfuerzo propio, a confirmar o corregir contra el validador real en T-505 (misma iteración empírica que ya funcionó con Factus en T-303/T-307)
  - Campos que el dominio no modela (medios/términos de pago, referencia del comprador) se omiten enteros, no se envían vacíos — cada muestra de referencia confirma que son opcionales (`minOccurs=0`) en el esquema real, mismo criterio de alcance que `FactusPayloadMapper` (T-303)
  - `unitCode` es una lista de alcance propio, no una traducción entre catálogos como en Factus: dominio y CII usan ambos UN/ECE Rec. 20, así que solo hace falta declarar qué códigos RC-1/2/3 usan (`C62`), cualquier otro falla fuerte
  - `mvn test -pl tributary-adapter-de-en16931 -Dtest=CiiInvoiceMapperTest` → `Tests run: 9, Failures: 0`. TDD: el test se escribió primero contra la API pública del mapeador (nunca contra las clases JAXB internas), visto en rojo (`cannot find symbol: CiiInvoiceMapper`)
  - **Bug real encontrado por el propio test, en `SecureXmlFactory` (T-500, ya commiteada):** `newDocumentBuilder()` nunca llamaba `setNamespaceAware(true)`. XRechnung/CII es un formato fuertemente namespaced (`rsm`/`ram`/`qdt`/`udt`); sin eso, `getLocalName()`/`getNamespaceURI()` devuelven `null` en cada elemento y `getElementsByTagNameNS(...)` no encuentra nada, silenciosamente — el documento generado era correcto de principio a fin, pero la propia herramienta de inspección del test (reutilizando `SecureXmlFactory.parse`, a propósito) no podía verlo. No es un problema de seguridad; T-500 nunca antes necesitó parsear un documento con namespaces reales. Corregido con una línea, sin efecto en ninguno de los tests de T-500/T-502 (namespace-awareness no interactúa con DOCTYPE/entidades/profundidad/tamaño)
  - **Prueba de falsabilidad:** se hizo que `buyerParty` adjuntara `SpecifiedTaxRegistration` incondicionalmente (incluso sin NIF). `buyerWithoutTaxIdOmitsTaxRegistration` lo detectó (`expected: <0> but was: <1>`). Revertido y re-verificado en verde (9/9)
- [x] **T-504** Integración del validador KoSIT con versión fijada y `kosit.sha256` — **CV-11** (T-012)
  - Verificación: el build falla si el checksum no coincide
  - **Dos artefactos de terceros, no uno, tratados los dos como cadena de suministro** (mismo razonamiento que gitleaks en T-002, y que T-012 exige explícitamente): el motor del validador (`itplr-kosit/validator` v1.6.2) y la configuración XRechnung (`itplr-kosit/validator-configuration-xrechnung` v2026-01-31) — un `scenarios.xml`/XSD/Schematron sustituido acreditaría documentos no conformes con la misma facilidad que un binario alterado, así que ambos quedan fijados en el mismo `validator/kosit.sha256`
  - Checksums obtenidos de dos fuentes independientes que coincidieron: el `digest` que la propia API de releases de GitHub reporta, y un `sha256sum` calculado localmente tras descargar los archivos de verdad — nunca se confió en el primero solo
  - `scripts/install-kosit-validator.sh`, mismo patrón fail-closed que `install-gitleaks.sh` (T-002): descarga a un directorio temporal, verifica los dos checksums con `sha256sum -c` **antes** de instalar cualquiera de los dos, aborta si alguno no coincide
  - **Verificado con una descarga real, no simulada:** ejecución real → `EXIT CODE: 0`, los dos artefactos instalados en `validator/` (gitignorado salvo `kosit.sha256`, según lo que T-001 ya había anticipado en `.gitignore`). Ejecución idempotente confirmada (segunda corrida detecta que ya está instalado, no vuelve a descargar)
  - **Canario real de checksum corrupto, mismo criterio que T-002:** se corrompió deliberadamente el hash del jar en `kosit.sha256`, se re-ejecutó el script → `validator-1.6.2-standalone.jar: FAILED`, `EXIT CODE: 1`, **ningún archivo instalado, ni siquiera el que sí coincidía** (el zip de configuración pasó su propio checksum pero no se instaló igual, por diseño). Restaurado el checksum real y re-verificado → `EXIT CODE: 0`
  - `KositValidator` (Java): invoca el jar real vía `ProcessBuilder` con la sintaxis de CLI confirmada empíricamente esta sesión (`-r <scenarios> -s <scenarios>/scenarios.xml -o <out> -p <archivo>`), parsea el informe XML real que produce. Señal de aceptación: el atributo `valid` de `<rep:report>` en la raíz, cruzado con la presencia de `<rep:assessment><rep:accept>` (confirmado en vivo: un documento con solo advertencias Schematron no fatales sigue reportando `valid="true"` — es el criterio "0 errores fatales" del SRS, no "0 hallazgos de ningún tipo")
  - `KositValidatorTest` reutiliza dos instancias CII reales y oficiales de `itplr-kosit/xrechnung-testsuite` como fixtures hermeéticos en `src/test/resources/kosit-samples/` (una válida, una con el campo obligatorio BT-44 removido) — mismo patrón que las fixtures de WireMock de fase 3, pero para XML. `@BeforeAll` ejecuta el script de instalación (igual que Testcontainers descarga `postgres:16` solo, sin paso manual aparte) para que `mvn test` siga siendo autocontenido
  - `mvn test -pl tributary-adapter-de-en16931 -Dtest=KositValidatorTest` → `Tests run: 2, Failures: 0`. Evidencia real impresa: `exitCode=0 accepted=true` / `exitCode=1 accepted=false ... findings mention BR-07: true`
  - **Prueba de falsabilidad:** el primer intento (fijar `valid=true` a mano) no detectó nada — el propio informe del caso rechazado real jamás incluye `<rep:accept>`, así que la segunda comprobación (`acceptNodes.getLength() > 0`) ya bastaba por sí sola para el caso de rechazo, exactamente el patrón de redundancia de L-021 pero encontrado de otra forma (una sonda que no discrimina, no un límite que coincide con un default). Ajustada a fijar `valid=false`: sí detectó el caso de aceptación (`officialValidSampleIsAccepted` falló). Revertido y re-verificado en verde (2/2)
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
