# tasks/lessons.md — Tributary

Memoria activa del proyecto. No vive en la cabeza de nadie: vive aquí, y cualquier agente o persona que retome el trabajo la lee antes de tocar código.

**Formato de entrada:**

```
## L-NNN · Título
**Fecha:** YYYY-MM-DD · **Origen:** decisión de diseño | corrección en implementación | hallazgo
**Qué pasó:** los hechos
**Por qué importa:** la consecuencia si se repite
**Regla derivada:** qué se hace a partir de ahora
**Cómo sabríamos que la regla falló:** señal observable
```

---

## L-001 · Un QR que apunta a la autoridad afirma algo que no ocurrió

**Fecha:** 2026-08-14 · **Origen:** corrección durante la revisión del SRS v0.9

**Qué pasó:** RF-003 especificaba generar el QR del régimen español "con la URL de verificación", mientras ADR-005 establecía que ningún registro se remite a la AEAT. Las dos cosas eran incompatibles y la contradicción sobrevivió hasta la revisión.

**Por qué importa:** un QR apuntando a la sede de la AEAT sobre un registro nunca remitido falla al escanearse y, peor, afirma que la factura está registrada. Un artefacto que miente hunde la credibilidad de todo el proyecto más de lo que la ausencia del artefacto la habría restado.

**Regla derivada:** el sistema nunca produce un artefacto que afirme algo que el sistema no hizo. El QR apunta al verificador propio y declara el modo no remitido (ADR-007, CV-12).

**Cómo sabríamos que la regla falló:** un test recorre el contenido del QR y falla si aparece cualquier host de la AEAT. Indicador temprano: cualquier PR que introduzca una URL externa en un artefacto generado sin una tarea que lo justifique.

---

## L-002 · Modelar sobre el proveedor en vez de sobre el estándar

**Fecha:** 2026-08-14 · **Origen:** decisión de diseño (ADR-001)

**Qué pasó:** el primer adaptador a implementar es el colombiano, y lo natural era modelar el dominio con la forma del JSON de Factus, porque es el único payload concreto disponible al empezar.

**Por qué importa:** habría acoplado el núcleo a un operador tecnológico específico. Cada régimen nuevo habría exigido rediseñar el dominio en lugar de escribir un traductor, y el proyecto habría perdido exactamente aquello que lo hace legible para un lector europeo.

**Regla derivada:** el dominio usa semántica EN 16931. Todo nombre de campo específico de un régimen vive en su adaptador y nunca cruza hacia adentro.

**Cómo sabríamos que la regla falló:** ArchUnit (CV-07) más una revisión de nombres: si aparece `cufe`, `reference_code` o `numbering_range` en `tributary-domain`, la regla ya falló.

---

## L-003 · Una respuesta perdida no es un fallo

**Fecha:** 2026-08-14 · **Origen:** decisión de diseño (ADR-003)

**Qué pasó:** el caso que rompe una integración con emisión irreversible no es el reintento evidente, es el timeout: el servidor procesó, la respuesta se perdió, y el cliente no sabe en qué estado está.

**Por qué importa:** reintentar produce un duplicado fiscal irreversible; no reintentar pierde una emisión. Las dos reacciones intuitivas son incorrectas.

**Regla derivada:** timeout produce `NEEDS_RECONCILIATION`. El reconciliador consulta al régimen por `reference_code` antes de decidir. Ninguna ruta emite sin haber consultado.

**Cómo sabríamos que la regla falló:** CV-10 (test de caos) más un test que afirma el orden de llamadas al mock. Indicador temprano: cualquier `catch (TimeoutException)` que llame a `issue()` en su cuerpo.

---

## L-004 · Una regla que nunca se vio fallar no prueba nada

**Fecha:** 2026-08-14 · **Origen:** patrón importado de CareLink

**Qué pasó:** en CareLink las reglas Semgrep propias se validaron primero contra código deliberadamente vulnerable, antes de aplicarlas al código real.

**Por qué importa:** una regla mal escrita pasa en verde sobre código vulnerable y produce confianza injustificada, que es peor que no tener la regla — porque nadie vuelve a mirar.

**Regla derivada:** toda regla de detección se observa fallando en rojo sobre un caso vulnerable antes de darse por buena. Aplica a Semgrep, a los triggers de base de datos y al verificador de cadena.

**Cómo sabríamos que la regla falló:** el repositorio contiene un directorio de casos deliberadamente vulnerables y el CI verifica que cada regla los detecta. Si el directorio se queda vacío o desactualizado, la regla falló.

---

## L-005 · El riesgo de una credencial que ya existe es distinto al de una que falta

**Fecha:** 2026-08-14 · **Origen:** resolución de P-01

**Qué pasó:** el SRS v0.9 modelaba R-01 como "las credenciales no llegan a tiempo". Al confirmarse que ya existen, el riesgo cambió de naturaleza: dejó de ser de planificación y pasó a ser de exposición.

**Por qué importa:** un riesgo que se resuelve favorablemente no desaparece, se transforma. Darlo por cerrado sin reevaluarlo deja un activo vivo sin control asignado.

**Regla derivada:** gitleaks en pre-commit además de CI, desde antes del primer commit que use credenciales. Si un secreto llega al historial, la remediación es rotar la credencial, no borrar el archivo.

**Cómo sabríamos que la regla falló:** `gitleaks detect` sobre el historial completo, no solo sobre el árbol de trabajo. Indicador temprano: un `.env` versionado o un valor por defecto en `application.yml`.

---

## L-006 · Validar con reglas transcritas por uno mismo es una tautología

**Fecha:** 2026-08-14 · **Origen:** resolución de P-04 (ADR-008)

**Qué pasó:** la alternativa barata para validar XRechnung era embeber XSD y Schematron propios. Habría producido un informe verde que solo demuestra que la implementación coincide consigo misma.

**Por qué importa:** el proyecto apunta a un lector alemán. Una validación contra la herramienta de referencia del ecosistema es evidencia; una validación contra las propias reglas es una afirmación circular.

**Regla derivada:** validador oficial KoSIT, con versión fijada y checksum verificado. La dependencia externa se acepta y su riesgo de cadena de suministro se trata explícitamente (T-012, CV-11).

**Cómo sabríamos que la regla falló:** si el checksum deja de verificarse "porque frena el build", el control se perdió. Indicador temprano: cualquier commit que añada `|| true` o `--skip` a la verificación del artefacto.

---

## L-007 · "Si sobra tiempo" no es una condición de entrada

**Fecha:** 2026-08-14 · **Origen:** resolución de P-02 (SRS §10.5)

**Qué pasó:** la entrega quedó definida como local, con exposición pública "si sobra tiempo". Formulado así, el despliegue habría ocurrido en el momento de menos margen, con el threat model de un entorno local.

**Por qué importa:** exponer a internet un sistema modelado para local es exactamente el fallo que el threat model existe para prevenir, y ocurriría precisamente cuando queda menos capacidad para revisarlo.

**Regla derivada:** la exposición pública es Milestone 2 y tiene un gate con siete casillas obligatorias (§10.5). Si alguna queda sin marcar, el sistema permanece local.

**Cómo sabríamos que la regla falló:** existe una URL pública sin las siete casillas marcadas en el repositorio. Indicador temprano: un `docker-compose.prod.yml` o una configuración de despliegue apareciendo antes de la fase 7.

---

## L-008 · El hook de pre-commit imprimía "COMMIT REJECTED" y salía 0

**Fecha:** 2026-08-15 · **Origen:** corrección en implementación (T-002)

**Qué pasó:** el hook de gitleaks se escribió así:

```bash
if gitleaks git --staged; then
  exit 0
fi
status=$?      # <-- siempre 0
exit "${status}"
```

En bash, `$?` después de un `fi` es el estado del **comando compuesto `if`**, no el de la condición. Cuando la condición falla y no hay rama `else`, el `if` completo devuelve 0. El hook detectó correctamente un `FACTUS_CLIENT_SECRET` staged, imprimió el banner de rechazo entero —incluida la instrucción de rotar la credencial— y devolvió `EXIT CODE: 0`. El commit entró al historial.

**Por qué importa:** el modo de fallo no fue "el control no existe", fue "el control informa que actuó y no actuó". Un hook silencioso se nota el día que alguien busca por qué no corre; uno que imprime un rechazo convincente no se nota nunca. Es el mismo patrón que L-004 y que CV-03: un control que produce confianza injustificada es peor que su ausencia, porque nadie vuelve a mirar. Y el activo detrás es A-002, cuyo compromiso es irreversible.

**Regla derivada:** todo control se verifica ejecutándolo contra el caso que debe bloquear, y el criterio de aceptación es el **código de salida observado**, nunca el mensaje impreso. Aplica a los hooks, a los triggers de PostgreSQL, al verificador de cadena y a las reglas Semgrep. Corolario en shell: capturar el estado del comando directamente (`cmd; status=$?`), nunca leer `$?` después de un `if`/`fi`, y en la rama de fallo salir con un literal no-cero en vez de una variable que podría valer 0.

**Cómo sabríamos que la regla falló:** el criterio de verificación de un control cita el mensaje que imprime en lugar del código de salida o del efecto observable ("devuelve `ERROR`" está bien; "muestra el aviso" no). Indicador temprano: cualquier script de control con `exit "$status"` donde `status` se leyó después de un bloque condicional, o un `set -e` ausente donde se asumía presente.

---

## L-009 · Un escáner de secretos cubre lo que cubre, y hay que medirlo antes de confiar

**Fecha:** 2026-08-15 · **Origen:** hallazgo durante T-002

**Qué pasó:** el primer canary para probar el hook fue la clave de ejemplo de AWS `AKIAIOSFODNN7EXAMPLE`. gitleaks 8.30.1 no la detectó, y una clave AWS de forma realista tampoco. Al medir el config por defecto contra siete patrones, la cobertura real resultó ser: `client_secret` en forma `ENV=`, YAML y properties de Spring → detectado; PAT de GitHub → detectado; claves AWS → no detectado.

**Por qué importa:** la conclusión cómoda habría sido "el canary no saltó, el hook no sirve" o —peor— "el canary no saltó, será que no hay secretos". La correcta es que un escáner tiene una cobertura concreta y desconocida hasta que se mide. Aquí terminó bien por accidente: lo que este proyecto necesita proteger es exactamente lo que sí detecta. Con otro formato de credencial, el mismo hook verde no habría probado nada.

**Regla derivada:** antes de apoyarse en una herramienta de detección, se prueban los patrones **de este proyecto** —el `client_secret` de Factus, el material de firma JWT, las claves por titular— y se documenta qué detecta y qué no. Un patrón relevante no cubierto se cierra con una regla propia en `.gitleaks.toml`, no se asume cubierto. Un canary que no salta es una hipótesis a investigar, nunca una respuesta.

**Cómo sabríamos que la regla falló:** un secreto con la forma que usa este proyecto pasa un `gitleaks dir .` en verde. Indicador temprano: `.gitleaks.toml` no existe o no tiene ninguna regla propia cuando ya se maneja una credencial de forma no estándar, y ningún caso de prueba deliberadamente vulnerable ejercita el escáner.

---

## L-010 · Una decisión que no está en el SRS todavía no está decidida

**Fecha:** 2026-08-15 · **Origen:** hallazgo durante fase 0

**Qué pasó:** ADR-009 —el endpoint `GET /api/v1/records/{id}/verification` como única ruta pública sin autenticar, con cuerpo restringido a seis campos— se comunicó por instrucción de sesión como "ya resuelto en el SRS v1.1". El documento del árbol de trabajo sigue declarando v1.0 y `grep -c ADR-009` devuelve 0.

**Por qué importa:** la decisión es buena y la instrucción es del autor, así que se implementa. Pero mientras el documento no la contenga, el repositorio tiene dos fuentes de verdad y la que §0 declara autorizada es la que no la tiene. Quien retome el trabajo en tres semanas leerá el SRS, no el historial de una conversación, y encontrará un endpoint sin autenticación que ningún documento justifica — que es exactamente la forma que tiene un hallazgo de seguridad de parecer un descuido.

**Regla derivada:** una decisión de arquitectura comunicada verbalmente se implementa, pero se registra como bloqueante abierto hasta que aterriza en `docs/SRS-tributary.md` por revisión versionada en §12. Abrir una ruta sin autenticar arrastra además la reevaluación de la etapa Reconnaissance de §7B: no es una fila nueva en §6.5 y ya está.

**Cómo sabríamos que la regla falló:** el código implementa un comportamiento que ningún documento del repositorio describe. Indicador temprano: un ADR citado en un comentario, en un mensaje de commit o en `tasks/todo.md` que no existe en el SRS.

---

## L-011 · El artículo determinado esconde los vacíos mejor que un `[PENDIENTE]`

**Fecha:** 2026-08-15 · **Origen:** hallazgo al preparar la fase 1

**Qué pasó:** el SRS v1.0 se aprobó declarando "sin `[PENDIENTE]` abiertos". Al ir a implementar aparecieron dos huecos que ninguna revisión había marcado, los dos escritos con artículo determinado:

- RF-005: *"pasa la validación para **los tres casos de prueba definidos**"*, y CV-05: *"sobre **los tres XML de referencia**"*. Nunca se definen. De ellos dependen T-101, T-104, T-505 y CV-05 entero.
- RF-001: *"un `businessKey` derivado **determinísticamente de la venta**"*. No dice de qué campos. De ahí cuelga ADR-003 completo.

**Por qué importa:** "los tres casos definidos" se lee como una referencia a algo que existe en otra parte del documento, y el ojo la da por resuelta. Un `[PENDIENTE]` explícito habría bloqueado la aprobación; el artículo determinado pasó una revisión técnica entera. Y el coste de descubrirlo tarde no es simétrico: si se hubiera detectado en la fase 5, CV-05 habría dictado retroactivamente qué debía modelar el dominio de la fase 1 — el estándar habría vuelto a ser rehén del último adaptador, que es exactamente lo que ADR-001 existe para impedir. Sobre `businessKey`, la elección por defecto cómoda (hash del contenido) habría hecho que dos pedidos distintos con las mismas líneas el mismo día colapsaran en una factura, y ese fallo solo aparece en producción con un cliente que repite un pedido.

**Regla derivada:** al empezar una fase se recorre el SRS buscando referencias con artículo determinado a artefactos que el documento no define —"los tres casos", "el subconjunto aplicable", "los campos mínimos", "la venta"—, y cada una se resuelve **antes** de escribir código de esa fase, registrando la resolución en `tasks/todo.md`. Un adjetivo como "determinístico" o "canonicalizado" describe una propiedad, no una especificación: hay que fijar los campos y su orden.

**Cómo sabríamos que la regla falló:** un test de una fase tardía obliga a cambiar el modelo de dominio de la fase 1. Indicador temprano: una tarea cuya implementación arranca con una decisión que nadie tomó por escrito, o un criterio de verificación que cita un artefacto sin ruta de archivo ni definición. Aplicar la misma sospecha a lo que queda: "los campos mínimos del RD 1007/2023" (RF-003) y "la huella sobre los campos canonicalizados" (T-400) todavía no están fijados.

---

## L-012 · Un test de propiedades no puede detectar una deriva del modo de redondeo

**Fecha:** 2026-08-15 · **Origen:** hallazgo al ejecutar la prueba de falsabilidad de T-100

**Qué pasó:** T-100 quedó en verde con 27 tests, de los cuales 10 son propiedades jqwik a 1000 casos cada una — 10.000 combinaciones generadas. Aplicando L-004 se cambió `Money.ROUNDING` de `HALF_UP` a `HALF_EVEN` para ver la suite fallar. Falló: `Tests run: 27, Failures: 3`. Pero los tres fallos fueron **tests de ejemplo**. Las diez propiedades pasaron todas: `FiscalArithmeticPropertyTest ... Tests run: 10, Failures: 0`.

**Por qué importa:** el criterio de verificación que el SRS asigna a T-100 es exactamente el que no detecta el fallo — *"test de propiedades jqwik: para cualquier combinación, base + impuesto = total con escala 2"*. Y no es un defecto de los tests escritos, es estructural: asociatividad, conmutatividad, escala 2 y `base + impuesto = total` son **leyes**, y se cumplen bajo `HALF_UP`, `HALF_EVEN`, `HALF_DOWN` y `FLOOR` por igual. El modo de redondeo no es una ley del álgebra, es una **elección normativa**, y una elección solo se fija clavando valores concretos. Con jqwik como único criterio, alguien "limpia" el redondeo a `HALF_EVEN` —que es el default bancario y el de `Math.round` en media docena de librerías— y los 10.000 casos siguen verdes mientras cada factura se desvía un céntimo.

**Regla derivada:** las propiedades cubren las leyes; los ejemplos clavan las decisiones. Toda constante normativa —modo de redondeo, escala, orden de canonicalización, algoritmo de huella— necesita al menos un test de ejemplo con un valor elegido para **discriminar** esa constante de sus alternativas plausibles, y el comentario dice cuál sería el resultado bajo la alternativa. En `MoneyTest`: `0.10 × 0.05 = 0.0050` da `0.01` bajo `HALF_UP` y `0.00` bajo `HALF_EVEN`. Un caso que da lo mismo bajo ambos modos —como `0.095 → 0.10`— no discrimina nada aunque parezca un empate.

Aplica directamente a lo que viene: la huella SHA-256 de T-400/T-401 tiene el mismo perfil. Una propiedad del tipo "recalcular la huella da el mismo valor" se cumple con **cualquier** canonicalización consistente, incluida una equivocada. Hace falta un vector de prueba con el hash literal esperado.

**Cómo sabríamos que la regla falló:** cambiar una constante normativa deja la suite en verde. El check es mecánico y barato: por cada constante de este tipo, alterarla a su alternativa plausible y confirmar que algún test se pone rojo. Indicador temprano: una tarea cuyo único criterio de verificación es un test de propiedades, o un test de ejemplo cuyo valor esperado coincide bajo dos modos de redondeo distintos.

---

## L-013 · Un ejemplo de dos elementos puede cancelarse por simetría sin que el algoritmo esté bien

**Fecha:** 2026-08-15 · **Origen:** hallazgo al aplicar la prueba de falsabilidad de L-012 a T-101

**Qué pasó:** `InvoiceTotals.compute` reparte un descuento de documento entre grupos de IVA (BG-23) de forma proporcional, con el último grupo absorbiendo el residuo de redondeo para que la suma dé exacta. El caso RC-2 (grupos al 7 % y 19 %, descuento 10.00 sobre una base de 110.00) fue el primer test escrito para probarlo, con el resultado correcto (100.00 exacto) verificado a mano. Al desactivar la absorción de residuo como sonda de falsabilidad, **RC-2 no lo detectó**: siguió en verde. La razón es aritmética, no un defecto del test — con dos grupos que se reparten el 100 % (proporciones `p` y `1-p`), si `10·p` redondea hacia arriba entonces `10·(1-p)` redondea hacia abajo por construcción, y las dos derivas se cancelan. RC-2, sin buscarlo, había elegido un caso simétrico.

**Por qué importa:** un test que pasa "por casualidad aritmética" da la misma confianza que uno que prueba algo real, hasta que alguien lo mira con sospecha. Sin la sonda de falsabilidad de L-004, este hueco habría quedado invisible: RC-2 seguiría en la suite, documentado como "la prueba de redondeo de BG-23", sin serlo.

**Regla derivada:** cuando un test ejemplifica un algoritmo de reparto o redondeo, además del caso "realista" del dominio hace falta al menos un caso construido para que la deriva de redondeo **no pueda cancelarse por simetría** — en la práctica, tres o más grupos con la misma proporción nominal (aquí, tres importes netos iguales repartiendo un descuento entre sí), donde cada grupo redondea en la misma dirección. Es la generalización de L-012: no alcanza con un caso de ejemplo, hace falta uno diseñado para que el error se acumule en vez de cancelarse.

**Cómo sabríamos que la regla falló:** una sonda de falsabilidad (L-004) sobre código de reparto/redondeo no encuentra ningún test que se ponga en rojo. Indicador temprano: un test de reparto con exactamente dos grupos o dos categorías — con dos elementos que suman el total, hay una probabilidad real de que cualquier error de redondeo se cancele por aritmética, no porque el código esté bien.

---

## L-014 · Un comando de verificación documentado puede no correr nunca, y nadie lo nota en un solo módulo

**Fecha:** 2026-08-15 · **Origen:** hallazgo al ejecutar T-105

**Qué pasó:** `.github/copilot-instructions.md` documenta `mvn test -Dtest=ArchitectureTest` como el comando de verificación de CV-07. Al ejecutarlo tal cual, tras crear `ArchitectureTest` en `tributary-api`, falló: `No tests matching pattern "ArchitectureTest" were executed!` — porque en un reactor multi-módulo, `surefire` por defecto exige que **cada** módulo filtrado encuentre al menos un test que coincida, y solo uno de los siete módulos contiene esa clase. El comando llevaba escrito así desde la aprobación del SRS, sin que nadie lo hubiera corrido contra un proyecto Maven real todavía — se escribió contra la intención, no contra el reactor.

**Por qué importa:** es exactamente la definición de §9A de un control "declarado, no verificado" — con el agravante de que el documento que lo declara es el que exige, en su propia primera línea, que todo lo demás en el repositorio ceda ante él por ser la fuente derivada del SRS. Un comando de verificación que no corre es peor que ausente: alguien lo copia, lo ve fallar, y pierde minutos dudando si el problema es el código en vez del comando — o peor, asume que el proyecto está roto.

**Regla derivada:** todo comando de verificación que aparece en un documento (SRS §9A, `copilot-instructions.md`, un README) se ejecuta literalmente, carácter por carácter, la primera vez que el artefacto que verifica existe — no se asume correcto por ser plausible. Si falla por fricción de herramienta (no por el control en sí), se arregla en la configuración compartida del proyecto (aquí, `failIfNoSpecifiedTests=false` en el `maven-surefire-plugin` del POM padre) y se deja constancia de que el arreglo no afloja nada (`testFailureIgnore` permanece en su default en todos lados).

**Cómo sabríamos que la regla falló:** un comando documentado produce un error de herramienta (no un fallo de test) la primera vez que alguien lo copia y lo pega. Indicador temprano: un comando de verificación en la documentación que nadie ha pegado en una terminal desde que se escribió — que es indistinguible, desde el documento, de uno que sí funciona.

---

## L-015 · No toda sonda de falsabilidad prueba algo — quitar una negación redundante no falsifica nada

**Fecha:** 2026-08-15 · **Origen:** hallazgo al aplicar L-004 a T-204

**Qué pasó:** `V5__application_roles.sql` otorga a `tributary_app` solo `SELECT, INSERT` sobre `fiscal_record`/`audit_event`, y añade un `REVOKE UPDATE, DELETE` explícito aunque el `GRANT` nunca los había incluido — documentado en el propio archivo como "explícito para que la ausencia se lea como decisión, no como descuido". Al aplicar la sonda de falsabilidad de L-004, el primer intento fue quitar ese `REVOKE` y re-ejecutar `ApplicationRoleGrantsTest`. Los tests siguieron en verde: el `UPDATE` seguía fallando exactamente igual, porque en PostgreSQL el permiso por defecto ya es denegar, y el `GRANT` nunca lo había concedido — el `REVOKE` era ceremonial, y quitar algo ceremonial no cambia el comportamiento.

**Por qué importa:** una sonda que no puede fallar no es una prueba de falsabilidad, es teatro que se parece a una. Si me hubiera conformado con "quité una línea y el test siguió en verde, qué robusto", habría registrado confianza donde no la había — el mismo patrón que L-004 existe para prevenir, aplicado ahora al proceso de verificación en sí mismo, no al código verificado.

**Regla derivada:** antes de aceptar el resultado de una sonda de falsabilidad, confirmar que la sonda **podía** haber fallado — es decir, que existe un cambio *distinto* capaz de producir el comportamiento contrario. Para permisos SQL: no alcanza con quitar un `REVOKE`; hay que **ampliar el `GRANT`** correspondiente (aquí, añadir `UPDATE` explícitamente) para demostrar que el test detecta la concesión indebida si llegara a existir. Aplica en general: modificar una guarda defensiva que ya era redundante con otra capa no es una sonda válida; hay que tocar la capa que de verdad sostiene la garantía.

**Cómo sabríamos que la regla falló:** una sonda de falsabilidad se declara "pasada" (el cambio no rompió nada) sin haber verificado primero que el cambio, de haber sido real, habría producido una diferencia observable. Indicador temprano: una sonda descrita como "quité X" sin una frase que explique qué comportamiento distinto se esperaría ver si X importara.

---

## L-016 · Fallos de entorno pueden parecerse a fallos de diseño, y los logs que lo aclararían pueden estar silenciados

**Fecha:** 2026-08-15 · **Origen:** hallazgo al montar el arnés Testcontainers de T-208

**Qué pasó:** al ejecutar los primeros tests de fase 2 contra Testcontainers, todos fallaron con `IllegalStateException: Could not find a valid Docker environment` — a pesar de que `docker run hello-world` funcionaba perfectamente en la misma máquina. La causa real, oculta detrás de dos capas: (1) sin ningún binding de SLF4J activo, los logs de diagnóstico de Testcontainers (que habrían mostrado el motivo exacto) se tragaban en silencio; (2) al añadir `slf4j-simple` para verlos, **seguían** en silencio, porque `HikariCP` trae transitivamente `slf4j-api:1.7.36` que ganaba por cercanía sobre el `2.0.17` que ya usaban `archunit`/`testcontainers`, y la versión vieja usa un mecanismo de binding (`StaticLoggerBinder`) incompatible con `slf4j-simple` 2.x. Solo tras fijar `slf4j-api` explícitamente aparecieron los logs reales, que revelaron la causa de fondo: la librería `docker-java` empaquetada en `testcontainers 1.21.3` negocia la API de Docker en la versión `1.32`, y el daemon de este entorno (Docker 29.3.0) exige mínimo `1.40` — una incompatibilidad de versión entre una librería cliente algo desactualizada y un daemon muy reciente, resuelta subiendo a `testcontainers 1.21.4`.

**Por qué importa:** el síntoma ("no encuentra Docker") apuntaba a un problema de configuración o permisos del entorno, y las dos primeras horas de diagnóstico se gastaron ahí (`DOCKER_HOST`, sockets, grupos de usuario) cuando el problema real era una versión de librería. El conflicto de `slf4j-api` es el tipo de defecto que el propio §5.3 pide fijar explícitamente ("sin rangos flotantes") pero que nadie ve hasta que hace falta un log que no aparece — no rompe la compilación, no rompe ningún test hasta que se necesita diagnosticar OTRA cosa.

**Regla derivada:** cuando un fallo de infraestructura de test no tiene una causa obvia en el código propio, verificar primero que los logs de diagnóstico de la herramienta en cuestión son visibles (¿hay un binding SLF4J activo? ¿en qué versión?) antes de sospechar de la configuración del entorno. `mvn dependency:tree | grep slf4j` es el primer comando, no el último. Mantener `slf4j-api` fijado explícitamente en cualquier módulo que dependa de librerías con logging propio (HikariCP, Testcontainers, Flyway), no confiar en que la resolución transitiva de Maven elija la versión correcta.

**Cómo sabríamos que la regla falló:** `mvn dependency:tree` muestra más de una versión de `slf4j-api` en el árbol de un módulo. Indicador temprano: un mensaje `SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder"` en la salida de cualquier build — es siempre señal de una versión vieja de `slf4j-api` ganando sobre un binding 2.x, nunca ruido inofensivo.

---

## L-017 · JSONB no es un espejo del texto que se le insertó — y eso rompe la reproducibilidad de una huella

**Fecha:** 2026-08-15 · **Origen:** hallazgo al construir `ChainVerifier` (T-206)

**Qué pasó:** `fiscal_record.canonical_payload` se diseñó como `JSONB` en `V1` — parecía la elección obvia para "carga canonicalizada" (§6.4). Al escribir `ChainVerifierTest.healthyChainIsIntact` con una cadena sana de tres registros recién insertados, el verificador reportó `BROKEN` en los tres. La causa: `SELECT '{"n":1}'::jsonb::text` en PostgreSQL devuelve `{"n": 1}` — con un espacio después de los dos puntos que el texto original no tenía. PostgreSQL normaliza el JSON al guardarlo en una columna `JSONB`; lo que se lee de vuelta no son los mismos bytes que se escribieron, son una re-serialización canónica **de PostgreSQL**, no la del sistema. Cualquier huella calculada sobre el texto original y comparada contra una recalculada desde `canonical_payload::text` divergía siempre, sin excepción — no por un error en la lógica de comparación, sino porque los dos lados nunca podían ser iguales por construcción.

**Por qué importa:** RF-003 exige textualmente que "la huella es reproducible: recalcularla desde los datos persistidos da el mismo valor" — y `JSONB` viola esa propiedad de forma silenciosa y sistemática, no ocasional. Sin el verificador de T-206 exigiendo esa reproducibilidad de inmediato, este defecto habría quedado dormido hasta la fase 4 (el adaptador ES, que sí calcula huellas reales), y ahí habría parecido un bug en la canonicalización de T-400/T-401 — una tarea entera de la fase equivocada gastada depurando un problema que en realidad estaba en el tipo de columna elegido dos fases antes.

**Regla derivada:** cualquier columna cuyo contenido se hashea, firma, o debe reproducirse byte a byte se declara `TEXT`, nunca `JSONB` ni ningún tipo que el motor pueda reformatear en el camino. `JSONB` es correcto para datos que se consultan o indexan (como `issuance_attempt.warnings`, que se queda en `JSONB` a propósito); es incorrecto para datos que se verifican criptográficamente. La pregunta a hacerse antes de elegir el tipo de una columna que alimenta un hash: "¿qué devuelve el motor si leo esto de vuelta ahora mismo, byte a byte contra lo que escribí?" — y probarlo, no asumirlo.

**Cómo sabríamos que la regla falló:** una prueba de round-trip (`INSERT` de un valor conocido, `SELECT` inmediato, comparación byte a byte) falla para cualquier columna que alimente una huella. Indicador temprano: un verificador de integridad que reporta `BROKEN` en el 100 % de los casos, incluidos los que nadie tocó — esa uniformidad es la firma de un problema estructural (el tipo de dato), no de manipulación real, que sí produciría discrepancias aisladas.

---

## L-018 · Una máquina de estados exhaustiva solo es exhaustiva contra los casos que alguien pensó

**Fecha:** 2026-08-15 · **Origen:** hallazgo al construir T-306 sobre T-102, ya commiteada

**Qué pasó:** `DocumentState` (T-102) se construyó con un test que recorre las 7×7 combinaciones posibles contra una tabla esperada — "exhaustivo" en el sentido de que cubre cada par, y pasó por su propia prueba de falsabilidad en su momento (L-004). Semanas de trabajo después (en tiempo de proyecto, no de reloj), al escribir `ReconcileInvoiceUseCase` para RF-008, un test simple (`FOUND_REJECTED` durante la reconciliación) lanzó `IllegalStateException: illegal transition: NEEDS_RECONCILIATION -> REJECTED`. La transición nunca se había declarado, porque RF-008 narra tres desenlaces (encontrado+validado, no encontrado, ambiguo) y **nunca dice qué pasa si la reconciliación encuentra un documento rechazado** — un desenlace real y alcanzable (`QueryOutcome.FOUND_REJECTED` ya existía desde T-103) que el SRS simplemente no enumeró en prosa.

**Por qué importa:** "exhaustivo" en T-102 significaba "cada combinación se decide explícitamente como permitida o prohibida", no "cada combinación que hará falta ya fue prevista". El test de 49 pares SÍ habría fallado si `NEEDS_RECONCILIATION -> REJECTED` se hubiera intentado usar sin declararla — y de hecho, eso es justo lo que pasó: falló, correctamente, en el momento en que el código intentó usarla. La máquina de estados hizo su trabajo (bloquear una transición no declarada); lo que faltaba era que alguien declarara la transición correcta, y eso solo se supo al construir el CONSUMIDOR real, no al diseñar el productor en aislamiento.

**Regla derivada:** una tabla de transiciones (o cualquier enumeración cerrada construida antes de que exista todo su consumidor) se trata como una hipótesis, no como un hecho cerrado, hasta que el ÚLTIMO caso de uso que la ejercita está construido. Cuando aparece un hueco, la corrección va en la fuente (aquí, `DocumentState` + su tabla independiente en el test), nunca como un parche en el consumidor que la rodea. Y el hueco se documenta con la razón real: no fue un error de tipeo, fue que la especificación (RF-008) tenía prosa incompleta que nadie notó hasta que el código necesitó la rama que la prosa había omitido.

**Cómo sabríamos que la regla falló:** un caso de uso nuevo lanza `IllegalStateException` desde una máquina de estados que ya se había dado por "completa" en una fase anterior. Indicador temprano: cualquier enumeración de resultados (`QueryOutcome`, `IssuanceOutcome`) con más valores que los que la máquina de estados que los consume sabe manejar — la asimetría entre "cuántos desenlaces puede reportar el puerto" y "cuántos sabe recibir el estado" es exactamente donde vive este tipo de hueco.

## L-019 · Un artefacto de documentación oficial describe el diseño previsto de la API, no necesariamente lo que el validador real exige hoy

**Fecha:** 2026-08-15 · **Origen:** T-307, chaos test contra el sandbox real de Factus

**Qué pasó:** `FactusPayloadMapper` (T-303) se construyó y verificó contra dos fuentes: pruebas manuales tempranas contra el sandbox real, y después un artefacto de documentación oficial de Factus que el usuario aportó explícitamente para "no depender de un fetch". Ese artefacto describe `names` como obligatorio solo cuando `legal_organization_code = "2"` (persona natural) — bajo esa lectura, un comprador tipo empresa (`legal_organization_code = "1"`, con `company` ya presente) no necesita `names`. El chaos test de T-307, al emitir de verdad contra el sandbox con un comprador con NIF, fue rechazado por el validador real con `"names": "El campo names debe ser una cadena de caracteres"` — el campo faltaba exactamente en el caso que la documentación decía que era opcional.

**Por qué importa:** el artefacto no estaba mal escrito ni obsoleto de forma evidente; simplemente describe una regla de negocio declarada que el validador real no aplica de forma tan estricta — factus exige `names` siempre, y usa `company` como el campo adicional para el caso persona jurídica, no como sustituto. Ninguna prueba contra WireMock (que solo repite las formas ya confirmadas) podía detectar esto, porque WireMock no valida nada — solo devuelve lo que se le programó. La única forma de descubrirlo fue una llamada real contra `/v2/bills/validate` con un payload real.

**Regla derivada:** un artefacto de documentación oficial (o cualquier documentación de terceros) fija la FORMA de una integración (nombres de campo, tipos, estructura del payload) con confianza razonable, pero no sustituye una corrida real contra el sistema real para las REGLAS DE VALIDACIÓN condicionales ("obligatorio solo si..."). Cuando el proyecto tiene acceso a un sandbox real (como aquí), al menos un camino de cada rama condicional documentada debe ejecutarse contra el sistema real antes de darla por cerrada, no solo contra el artefacto. La corrección se aplicó directamente en `FactusPayloadMapper.customer()` (enviar `names` siempre, no condicionalmente) y quedó documentada en el propio código y en `FactusPayloadMapperTest`, no solo aquí.

**Cómo sabríamos que la regla falló:** el sandbox real rechaza un payload que el mapeador construyó siguiendo al pie de la letra una condición documentada ("obligatorio solo si X"), con un mensaje de validación que apunta a un campo que la documentación marcaba como opcional en ese caso.

## L-020 · Un endpoint de listado no tiene por qué exponer los mismos campos que el endpoint que creó el recurso

**Fecha:** 2026-08-15 · **Origen:** T-307, chaos test contra el sandbox real de Factus

**Qué pasó:** RF-008 (reconciliación) describe adoptar "el CUFE" del documento encontrado al consultar por `reference_code`. `FactusQueryGateway` (construido para T-306/T-307) inicialmente extraía `match.get("cufe")` de la respuesta de `GET /v2/bills?filter[reference_code]=...`, siguiendo esa prosa literalmente. El chaos test de T-307, al ejecutar el paso de reconciliación real, lanzó `NoSuchElementException` — el campo simplemente no está en los elementos que ese endpoint devuelve. Confirmado con `curl` directo contra el sandbox: los elementos de `GET /v2/bills` traen `number`, `is_validated`, `reference_code`, entre otros, pero nunca `cufe`. Se probaron variantes de endpoint de detalle (`show-bill/{number}`, `show-reference-code/{key}`, `/v2/bills/{key}`) buscando un lugar donde sí apareciera — las tres devolvieron 404 contra el sandbox real.

**Por qué importa:** el CUFE sí existe y sí se puede obtener — pero únicamente en la respuesta de la creación original (`POST /v2/bills/validate`), que T-304 ya captura y persiste en `issuance_attempt` en el momento de la emisión. RF-008 describe la reconciliación como si "consultar y adoptar el CUFE" fuera una sola operación simétrica a la emisión, pero la API real separa "crear" (devuelve CUFE) de "listar/confirmar" (devuelve `number`, no CUFE) como dos formas de respuesta distintas, no un subconjunto una de la otra.

**Regla derivada:** cuando una tarea describe "consultar X y adoptar Y" contra una API de terceros, verificar contra una llamada real que el endpoint de consulta realmente expone Y, no asumir que cualquier endpoint relacionado con el mismo recurso comparte el mismo esquema de respuesta que el endpoint que lo originó. La solución no fue forzar que apareciera un CUFE inexistente, sino reconocer qué garantía real puede dar cada operación: la reconciliación de T-307 adopta `number` (lo único que el endpoint de consulta puede confirmar), y el CUFE fiscal permanece como el que ya se capturó en la emisión original — documentado explícitamente como una desviación de la redacción literal de RF-008 en el Javadoc de clase de `FactusQueryGateway`, no escondido.

**Cómo sabríamos que la regla falló:** una integración nueva contra un endpoint de "listado" o "consulta" de terceros asume, sin una llamada real de por medio, que trae el mismo campo que el endpoint de "creación" del mismo recurso.

## L-021 · Un límite de seguridad que coincide con el valor por defecto del JDK no está probado por una sonda que solo lo quita

**Fecha:** 2026-08-16 · **Origen:** T-500, `SecureXmlFactory`

**Qué pasó:** antes de escribir `SecureXmlFactory`, se probó empíricamente contra el JDK real de este entorno (25.0.2) — no contra la documentación — qué hacía un `DocumentBuilderFactory` completamente sin configurar. Dos hallazgos: (1) sin ninguna protección, el parser **sí** filtra el contenido real de `/etc/passwd` al DOM — el XXE es real, no teórico, en este entorno; (2) ese mismo JDK, sin ninguna configuración explícita, **ya** rechaza una anidación de más de 100 niveles y más de 2500 expansiones de entidad — límites de fábrica (`jdk.xml.maxElementDepth=100`, `jdk.xml.entityExpansionLimit=2500`) de una versión relativamente reciente de JAXP, no garantizados en cualquier JDK. Si `SecureXmlFactory` hubiera fijado su propio límite de profundidad en 100 (el mismo valor "razonable" que cualquiera elegiría sin verificar), una sonda de falsabilidad que quitara esa línea **no habría detectado nada** — el valor por defecto del JDK habría seguido bloqueando el mismo caso de prueba, exactamente el error que L-015 ya describe para una `REVOKE` redundante, aquí aplicado a XML en vez de SQL.

**Por qué importa:** §5.3 exige límites explícitos "sin configuración por defecto heredada de la librería" — no por estilo, sino porque un valor por defecto puede cambiar entre versiones/distribuciones del JDK sin aviso, y porque un límite que coincide por casualidad con el valor por defecto es indistinguible, en una prueba, de no tener ningún límite propio en absoluto. `MAX_ELEMENT_DEPTH` se fijó deliberadamente en 64 (no 100) precisamente para que fuera menor que el default de este JDK y así la sonda de falsabilidad tuviera algo real que detectar — confirmado: quitar la línea `setAttribute(maxElementDepth, 64)` hizo que el test de profundidad (documento de 74 niveles, sin entidades) pasara de rojo a verde silenciosamente, porque 74 sigue por debajo del default del JDK (100).

**Regla derivada:** al fijar un límite de seguridad explícito que probablemente ya tiene un valor por defecto razonable en la plataforma (profundidad, tamaño, timeouts), primero medir empíricamente cuál es ese default en el entorno real, y elegir un valor explícito que sea estrictamente más estricto que él — nunca igual ni más permisivo. Si el valor elegido coincide con el default, la sonda de falsabilidad de esa tarea no prueba que el código propio hace algo; prueba que la plataforma ya lo hacía por su cuenta.

**Cómo sabríamos que la regla falló:** una sonda de falsabilidad que quita un límite explícito no cambia el resultado de ningún test — el mismo síntoma exacto que L-015 documentó para una regla SQL redundante, aquí en un contexto distinto (hardening de parser XML en vez de permisos de base de datos).

## L-022 · Una clave de cifrado por fila y una referencia de integridad hacia esa misma fila pueden bloquearse mutuamente

**Fecha:** 2026-08-16 · **Origen:** T-601, cifrado de PII del comprador

**Qué pasó:** `subject_key.subject_id` se diseñó con `REFERENCES buyer(id)` — una decisión deliberada de T-600 para que la tabla de claves nunca pudiera apuntar a un comprador inexistente. `JdbcInvoiceRepository.insertBuyer` generaba el `id` del comprador, pedía su clave a `KeyVaultPort.getOrCreateKey(id)` **antes** de insertar la fila, y usaba esa clave para cifrar `name`/`address`/`email`/`phone` en el mismo `INSERT`. La primera corrida real (no una sonda, un fallo genuino al conectar T-600 y T-601 por primera vez) lo rechazó: `insert or update on table "subject_key" violates foreign key constraint` — la clave no puede existir antes que la fila que la referencia, pero la fila no puede cifrarse sin la clave.

**Por qué importa:** las dos decisiones eran correctas por separado (T-600: la FK evita claves huérfanas; el cifrado necesita la clave antes de escribir el dato cifrado) y solo se contradicen cuando se combinan en el mismo flujo de escritura. Ninguna prueba unitaria de `JdbcKeyVaultRepositoryTest` ni de `PiiCipherTest` podía revelar esto — cada una probaba su propia pieza con datos ya consistentes (un comprador ya insertado, una clave ya generada). Solo apareció al conectar ambas piezas en `JdbcInvoiceRepositoryTest`, ejecutando la ruta real de escritura completa.

**Regla derivada:** cuando una fila necesita una clave/recurso que a su vez referencia esa misma fila por FK, el orden de escritura no puede ser "generar clave → insertar fila cifrada" en un solo paso — hace falta insertar la fila primero (con un valor no cifrado válido para la restricción NOT NULL, no un valor con significado), pedir la clave después, y actualizar la fila con los valores cifrados en un segundo statement. La ventana entre ambos statements no es una inconsistencia real si nada más puede observar esa fila todavía (su id no se ha devuelto a ningún otro llamador).

**Cómo sabríamos que la regla falló:** cualquier tabla nueva con una FK hacia un recurso "por fila" (una clave, un token, una credencial específica de esa fila) que se intenta poblar en el mismo INSERT que genera el id al que esa FK apunta.

## L-023 · Importar un BOM externo puede resucitar un conflicto de versión ya resuelto una vez

**Fecha:** 2026-08-16 · **Origen:** fase 7, al añadir Spring Boot 3 a `tributary-api`

**Qué pasó:** al importar `spring-boot-dependencies:3.5.3` como `dependencyManagement` en `tributary-api` (necesario para T-700+), los tests de Testcontainers (`IssueInvoiceIntegrationTest`, `IssueInvoiceConcurrencyTest`, `VerifactuChainIntegrationTest`) volvieron a fallar con `Could not find a valid Docker environment` — el mismo síntoma exacto que L-016 ya documentó y resolvió (fase 2) fijando `testcontainers` en `1.21.4`. `mvn dependency:tree` mostró la causa: el BOM de Spring Boot trae su propia versión gestionada (`1.21.2`) de `org.testcontainers:jdbc`/`testcontainers`, que ganaba en algunas aristas transitivas pese a que este módulo ya declaraba `1.21.4` explícito en sus propias dependencias directas.

**Por qué importa:** una versión fijada explícitamente en ESTE módulo no protege contra un BOM importado que gestiona la MISMA librería con otra versión — Maven no avisa de la colisión, y el síntoma (Testcontainers no encuentra Docker) es indistinguible del problema original que L-016 ya arregló, así que sin `dependency:tree` de por medio parece un regreso misterioso de un bug ya cerrado, no una causa nueva y explicable.

**Regla derivada:** después de importar cualquier BOM externo (`dependencyManagement` con `scope=import`) en un módulo que ya tiene versiones fijadas propias, correr `mvn dependency:tree` sobre ESE módulo específico y comparar contra las versiones que se esperaban — no asumir que una versión declarada explícitamente en el propio POM automáticamente gana sobre todo lo que un BOM gestiona. Cuando choquen, fijar la versión correcta con una entrada explícita de `dependencyManagement` DESPUÉS del `import` del BOM (gana sin importar el orden de los imports).

**Cómo sabríamos que la regla falló:** un test que ya pasaba (especialmente uno con Testcontainers) empieza a fallar justo después de añadir o actualizar un BOM externo, con un síntoma que no tiene relación aparente con el cambio hecho.

## L-024 · Un 403 de Spring Security puede no venir de una regla de autorización en absoluto

**Fecha:** 2026-08-16 · **Origen:** fase 7, primer test de extremo a extremo contra la API REST real

**Qué pasó:** el flujo completo RC-1 (registrar → emitir → leer) devolvía `403 FORBIDDEN` en el paso de emisión, con un `OPERATOR` real, token válido, rol correcto. Se sospechó primero del `AuthorizationManager` a medida — lógica hecha a mano para `hasRole`/`hasAnyRole` — y se instrumentó con impresiones de depuración directamente en el filtro. La instrumentación mostró, sin ambigüedad, `authorities=[ROLE_OPERATOR]` y `granted=true` para la ruta y el método exactos de la petición que fallaba. La causa real apareció recién en el log del servidor, no en la aserción del test: `IllegalArgumentException: Name for argument of type [java.lang.String] not specified... Ensure that the compiler uses the '-parameters' flag` — `@PathVariable String businessKey` (sin nombre explícito en la anotación) no podía resolverse porque el compilador de Maven no conserva los nombres de parámetro por defecto. Esa excepción, lanzada durante la resolución de argumentos del controlador (después de que la autorización YA había concedido acceso), terminaba manifestándose como `403` en la respuesta HTTP en lugar de un `500` — el código de estado por sí solo apuntaba directamente a la capa equivocada.

**Por qué importa:** un `403` se lee instintivamente como "la autorización lo rechazó", y en este caso esa lectura era categóricamente falsa — la autorización había concedido acceso limpiamente, dos veces, confirmado con evidencia impresa. Sin esa instrumentación server-side (no solo mirar el código de estado del lado del cliente), la investigación se habría quedado revisando indefinidamente una lógica de roles que ya funcionaba, buscando un bug que no estaba ahí.

**Regla derivada:** cuando un código HTTP de seguridad (401/403) aparece en un lugar inesperado, no asumir de qué capa viene por el código solo — instrumentar el punto de decisión real (aquí, el propio `AuthorizationManager`) con evidencia concreta (¿qué autoridades tiene, qué decisión tomó, para qué ruta) antes de seguir revisando la lógica que YA se sospecha. Y, por separado: cualquier módulo Spring MVC que use `@PathVariable`/`@RequestParam` sin nombre explícito en la anotación necesita el flag `-parameters` del compilador — Spring Boot lo activa por defecto en proyectos generados por Initializr, pero un `pom.xml` construido a mano (como el de este proyecto, con versiones fijadas explícitas en cada dependencia) no lo hereda automáticamente.

**Cómo sabríamos que la regla falló:** cualquier endpoint con un `@PathVariable`/`@RequestParam` sin nombre explícito falla en tiempo de petición (no de compilación) con exactamente ese mensaje, típicamente disfrazado de un código de estado de seguridad en vez de un error de framework.

---

## L-025 · Un test de un filtro de logging que fija `SecurityContextHolder` a mano no prueba que el filtro funcione en la cadena real

**Fecha:** 2026-08-16 · **Origen:** fase 7 / T-702, auditoría SQLi con tráfico real contra una instancia levantada de verdad

**Qué pasó:** T-701 (`RequestLoggingFilter`) leía `SecurityContextHolder.getContext().getAuthentication()` en su propio bloque `finally`, después de que `filterChain.doFilter(...)` retornara. `RequestLoggingFilterTest` (la suite escrita para T-701) fijaba `SecurityContextHolder` directamente y llamaba al filtro sin pasar por la cadena real de Spring Security — y pasaba en verde. Contra la aplicación real y levantada de verdad (necesaria para lanzar `sqlmap` en T-702), el log de acceso mostraba `"actor":"anonymous"` en el 100 % de las peticiones, incluidas peticiones `POST /api/v1/invoices` exitosas (`201`) con un `OPERATOR` autenticado real. Causa raíz, confirmada leyendo el bytecode de `SecurityContextHolderFilter` (`javap`): ese filtro de Spring Security, posicionado más adentro en la cadena que `RequestLoggingFilter`, ejecuta `SecurityContextHolder.clearContext()` en su propio `finally`, inmediatamente después de que el resto de la cadena retorna — es decir, antes de que el control vuelva a subir hasta el `finally` de `RequestLoggingFilter`, que queda leyendo un contexto ya vaciado, siempre, sin importar si la petición se autenticó o no.

**Por qué importa:** el campo `actor` es el propósito entero de un log de auditoría de acceso — sin él, el log registra que "alguien" hizo cada cosa, nunca quién. El bug era invisible en la suite unitaria precisamente porque esa suite evitaba el único mecanismo (el orden real de la cadena de filtros, con `SecurityContextHolderFilter` de por medio) responsable del bug. Solo apareció al lanzar tráfico real contra la aplicación real — el mismo patrón que L-024: un código o comportamiento observado en producción no explicado por ningún test existente es señal de que algún test está probando una versión más simple del sistema que la que realmente corre.

**Regla derivada:** un filtro que necesita el resultado de la autenticación (establecida por Spring Security más adentro en la cadena) no puede leerlo de forma fiable desde el `finally` de un filtro posicionado más afuera — para entonces, el `SecurityContextHolderFilter` de Spring ya limpió el `ThreadLocal`. La solución: un filtro aparte (`ActorCaptureFilter`), posicionado justo después de que la autenticación JWT resuelve (`addFilterAfter(..., BearerTokenAuthenticationFilter.class)`) y antes de la decisión de autorización, que fija el actor en un **atributo de la petición** (`HttpServletRequest.setAttribute`, no un `ThreadLocal` — sobrevive el `clearContext()` posterior) para que el filtro de logging, posicionado más afuera, lo lea de vuelta. Y, más en general: cualquier test de un filtro de seguridad/logging que fija su propio `SecurityContextHolder` en vez de pasar por la cadena real wired (`SecurityConfig`) no prueba el comportamiento desplegado — necesita un test de integración complementario (`ActorLoggingIntegrationTest`) contra la aplicación real.

**Cómo sabríamos que la regla falló:** el log de acceso real (`grep '"tributary.access"' | grep actor`) muestra `"anonymous"` en una petición que se sabe autenticada — visible con una sola línea de `grep` contra logs reales, nunca solo mirando el código fuente del filtro.

---

## L-026 · El resumen de códigos HTTP que un cliente de fuzzing se autorreporta no es la verdad del servidor

**Fecha:** 2026-08-16 · **Origen:** T-702, auditoría de SQLi con `sqlmap` contra la aplicación real levantada

**Qué pasó:** una corrida de `sqlmap --level 3 --risk 2` contra el cuerpo JSON de `POST /api/v1/invoices` terminó con `sqlmap` autorreportando `403 (Forbidden) - 2409 times` en su resumen de códigos HTTP — un número exacto, reproducido idéntico en una segunda corrida independiente contra una instancia recién reiniciada. Cruzado contra el propio log de acceso estructurado de la aplicación (T-701, `"tributary.access"`) para la ventana temporal exacta de esa corrida: **cero `403` en toda la historia real del servidor** — únicamente `201`, `400` y `200`, los tres con el actor correcto. El servidor nunca devolvió un solo `403`; el resumen que `sqlmap` mostró para esa corrida era, en su modo de prueba de cuerpo JSON, un artefacto interno del propio cliente.

**Por qué importa:** de haber confiado en el resumen de `sqlmap` sin cruzarlo, la conclusión de la auditoría habría sido "algo está devolviendo 2409 rechazos de autorización" — una pista falsa que habría disparado una investigación entera sobre un problema de autenticación/autorización que nunca existió. La verificación cruzada con el log real de la aplicación (posible precisamente porque T-701 ya deja una traza de acceso completa y honesta) evitó perseguir un fantasma.

**Regla derivada:** el resultado de una herramienta de fuzzing/pentest contra un objetivo propio nunca se acepta solo por su resumen autorreportado — se cruza contra una fuente de verdad del lado del servidor (aquí, el log de acceso estructurado real) para la misma ventana temporal antes de sacar cualquier conclusión, tanto si el resultado es "vulnerable" como si es "no vulnerable". La propia conclusión final de "no inyectable" de esta tarea se sostiene en la evidencia del servidor, no en el resumen del cliente.

**Cómo sabríamos que la regla falló:** una conclusión de seguridad citada en `todo.md` que solo referencia la salida de una herramienta externa, sin una línea de log o consulta del lado servidor que la corrobore de forma independiente.

---

## L-027 · Semgrep ignora por defecto cualquier ruta que contenga el segmento "tests" — sin avisar, solo "Nothing to scan"

**Fecha:** 2026-08-16 · **Origen:** T-703, reglas Semgrep propias

**Qué pasó:** los fixtures deliberadamente vulnerables para probar las reglas propias se guardaron primero en `.semgrep/tests/`. `semgrep --config .semgrep/tributary-rules.yml .semgrep/tests/` reportó `Scanning 0 files ... Nothing to scan` — ni un error ni una advertencia obvia, solo un resumen que decía "0 hallazgos" exactamente igual que si las reglas hubieran validado correctamente un código limpio. La causa: el `.semgrepignore` incorporado de Semgrep excluye por defecto cualquier ruta con el segmento `tests` (pensado para no escanear la propia suite de tests de una aplicación en busca de vulnerabilidades de producción) — visible solo con `--verbose` (`Scan skipped: Files matching .semgrepignore patterns: 3`).

**Por qué importa:** un directorio de fixtures ignorado silenciosamente produce el mismo resultado superficial ("0 findings") que reglas correctamente validadas — el escenario exacto que la disciplina de "la regla debe fallar en rojo antes de confiar en ella" existe para prevenir. Sin mirar el resumen con atención (o sin `--verbose`), es fácil confundir "no se escaneó nada" con "se escaneó y no hay nada".

**Regla derivada:** los fixtures de prueba para reglas propias de Semgrep viven en un directorio que no coincida con los patrones por defecto de `.semgrepignore` — `.semgrep/fixtures/`, no `.semgrep/tests/`. Y, en general: cuando una herramienta de análisis estático reporta "0 hallazgos", confirmar primero cuántos archivos escaneó realmente (`Targets scanned`), no solo el conteo de hallazgos — un `0` de `0 archivos` y un `0` de `91 archivos` se ven idénticos en el resumen corto.

**Cómo sabríamos que la regla falló:** `scripts/run-semgrep.sh` verifica explícitamente que las fixtures produzcan el número exacto de hallazgos esperado (4) antes de confiar en el resultado del escaneo real — si ese conteo cae a 0, el script falla con un mensaje explícito en vez de continuar en silencio.

---

## L-028 · "Versión fijada" no significa "versión seguraindefinidamente" — el pin necesita revisión activa, no solo existir

**Fecha:** 2026-08-16 · **Origen:** T-704, construyendo el paso de SCA del pipeline de CI

**Qué pasó:** al conectar `trivy` (SCA) contra el árbol de dependencias real del proyecto — todas las versiones ya fijadas explícitamente, tal como pide SRS 5.3 — apareció una lista real de CVE **HIGH/CRITICAL vigentes**: `tomcat-embed-core:10.1.42` con tres CRITICAL (RCE/bypass), `spring-security-web:6.5.1` con un CRITICAL (bypass de política de seguridad), más HIGH en `spring-security-core`, `spring-core`, `spring-webmvc`, `spring-expression`, `jackson-databind`, `jackson-core` y `org.postgresql:postgresql`. Ninguna de estas versiones era vieja por descuido — todas habían sido fijadas deliberadamente en fases anteriores del proyecto, algunas hace apenas unos días de trabajo — pero el ecosistema siguió publicando CVEs contra ellas después de fijarlas.

**Por qué importa:** la disciplina de SRS 5.3 ("versiones fijadas, nunca rangos") existe para build reproducibles, no para congelar el proyecto contra vulnerabilidades nuevas — confundir ambas cosas deja un pin envejecer en silencio hasta convertirse exactamente en el tipo de hallazgo que el Definition of Done promete bloquear ("sin hallazgos de dependencias high/critical"). Sin un SCA corriendo de verdad (no solo mencionado en la SRS como tarea futura), esos CVEs habrían permanecido invisibles hasta T-708 o más tarde.

**Regla derivada:** un pin de versión se corrige subiendo a otra versión igualmente fijada y exacta (nunca a un rango) tan pronto un escáner de SCA real lo señala — no se pospone como "hallazgo conocido" cuando la corrección es directa (aquí: `spring-boot.version` 3.5.3→3.5.16 en `tributary-api`, que arrastra tomcat/spring-security/spring-framework a versiones corregidas transitivamente; `spring-jdbc.version`/`postgresql.version`/un pin explícito de `jackson-databind` en `tributary-persistence`, que no importa el BOM de Spring Boot y por eso no se benefició del primer cambio — ver L-023 sobre por qué ese módulo tiene sus propios pines independientes). Cada subida se verificó con `mvn dependency:tree` (la versión resuelta real, no la que se *cree* que quedó) y con la suite completa en verde antes de confiar en ella. El SCA corre en cada push/PR (T-704), no una sola vez al fijar las versiones.

**Cómo sabríamos que la regla falló:** `trivy fs --severity HIGH,CRITICAL --exit-code 1` en CI pasa a rojo — que es exactamente la señal que se diseñó para disparar, y por lo que el job de `sca` es bloqueante y no informativo.
