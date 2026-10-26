# Contrato UML documental v2

## Separación de modelos

- **Documento UML**: JSONB versionado que conserva semántica, IDs estables, geometría y metadatos externos.
- **React Flow**: proyección visual. `type: "entity"`, posiciones, tamaños y handles no determinan la semántica UML.
- **ApplicationSchema**: contiene dos proyecciones separadas. La proyección CRUD consume `ENTITY` y `ASSOCIATION_CLASS` únicamente cuando tiene metadatos persistentes de entidad asociativa. La proyección estructural conserva `CLASS`, `INTERFACE`, `ENUMERATION` y clases de asociación no persistentes para generar tipos de dominio sin JPA.

`revision` continúa siendo la revisión optimista del registro y es independiente de `version`.

## Compatibilidad

Los documentos v1 permanecen válidos y no se reescriben al cargarse. El editor escribe v2 sólo cuando aparecen elementos o relaciones UML avanzados. En v2, la ausencia de `data.kind` se interpreta como `ENTITY` para permitir importaciones incrementales y adaptadores heredados.

## Nodos v2

Cada nodo conserva `id`, `position` y `data.id`. `data.kind` admite:

- `ENTITY`: entidad persistente generable.
- `CLASS`: clase UML no persistente.
- `INTERFACE`: interfaz UML.
- `ENUMERATION`: enumeración UML con `literals`.
- `ASSOCIATION_CLASS`: clase asociativa explícita.

Los atributos conservan el contrato SQL anterior y pueden añadir `visibility` y `static`. Los métodos contienen `id`, `name`, `visibility`, `returnType`, `parameters`, `static` y `abstract`. Cada parámetro tiene `id`, `name` y `type`. La visibilidad canónica es `public`, `private`, `protected` o `package`.

`abstract`, `width` y `height` son metadatos opcionales del nodo. `externalMetadata` conserva IDs de Enterprise Architect.

Una `ENUMERATION` recién creada puede persistirse con `literals: []` como estado de borrador. Los literales deben ser únicos cuando existan y una enumeración no puede simularlos mediante `attributes`. La generación de código, a diferencia de la persistencia, exige al menos un literal y devuelve un diagnóstico específico si el borrador sigue vacío.

## Relaciones v2

`data.relationKind` admite `ASSOCIATION`, `GENERALIZATION`, `REALIZATION`, `AGGREGATION`, `COMPOSITION` y `DEPENDENCY`. Se conservan `source`, `target`, IDs, nombre, multiplicidades y cardinalidades heredadas.

Una realización debe terminar en una interfaz. Las enumeraciones no participan en generalización. Agregación y composición no se convierten automáticamente en relaciones de base de datos.

## Límites verificados

- La edición visual cubre creación de entidades, clases, interfaces y enumeraciones; edición de nombre, atributos y métodos básicos; y selección/edición del tipo de relación.
- Los parámetros se preservan y validan, pero todavía no cuentan con editor visual dedicado.
- La importación EA representa clases, atributos, asociaciones, clases de asociación, generalizaciones y posiciones. El parser normalizado todavía no expone operaciones, interfaces, enumeraciones, realizaciones, agregaciones ni composiciones; por tanto, el round-trip automatizado sólo está cubierto para el subconjunto indicado.
- La exportación XMI UML 2.1 incluye paquete explícito, clases, entidades, clases de asociación, interfaces, enumeraciones/literales, atributos, operaciones/parámetros, generalización, realización, dependencia, asociación, agregación, composición y multiplicidades. Las asociaciones declaran `memberEnd`/`ownedEnd` coherentes y los tipos usan referencias UML o a clasificadores. Conserva IDs externos de EA cuando existen.
- La variante exportada incluye `xmi:Extension` de Enterprise Architect con paquete propietario `EAPK_*`, elementos, conectores, diagrama, propietario, identificador local, referencias, posiciones y tamaños derivados de React Flow. Tanto clasificadores como conectores aparecen como elementos visuales del diagrama. Las coordenadas se trasladan para que el mínimo del lienzo comience en `(100,100)` y se conservan distancias relativas.
- La generación Spring Boot/Flutter conserva el CRUD de entidades y añade esqueletos estructurales Java/Dart para clases, interfaces y enumeraciones. Generalización y realización se generan sólo entre clasificadores estructurales compatibles. Los métodos no reciben lógica inventada: lanzan explícitamente `UnsupportedOperationException`/`UnimplementedError` o permanecen como contratos abstractos.
- Un `ASSOCIATION_CLASS` con metadatos asociativos conserva el tratamiento persistente existente; sin esos metadatos se genera únicamente como tipo estructural. Agregación y composición no se convierten a JPA.
- Un diagrama sin entidad persistente continúa rechazándose con una indicación accionable. Los tipos desconocidos se rechazan nombrando miembro y tipo; los enums pueden ser usados por clases estructurales, pero todavía no por campos CRUD/JPA.
- Las operaciones de IA avanzadas requieren ampliar sus modelos semánticos; por seguridad siguen limitadas al subconjunto de entidades existente.

## Notación visual

El editor distingue clases, interfaces y enumeraciones, muestra estereotipos, compartimentos, visibilidad, tipos, operaciones, abstracción y miembros estáticos. Las relaciones usan triángulo hueco para generalización, trazo discontinuo y triángulo para realización, rombos hueco/sólido para agregación/composición y flecha abierta discontinua para dependencia. El nombre y las multiplicidades se renderizan junto a la relación.

## Hardening y causas corregidas

- El exportador XMI heredado proyectaba primero el diagrama a `ApplicationSchema`, donde sólo existen entidades persistentes, y después intentaba exportar todos los nodos y relaciones originales. Un clasificador UML no tenía clase correspondiente y hacía fallar el archivo completo. Los documentos v2 usan ahora un exportador UML dedicado; los documentos v1 conservan el flujo anterior.
- El generador filtraba nodos UML no persistentes, pero dejaba relaciones que todavía los referenciaban. El validador recibía entonces extremos inexistentes y devolvía “datos incompatibles”. La proyección v2 filtra conjuntamente nodos y relaciones; no genera enumeraciones ni clases como entidades de forma silenciosa.
- Los endpoints de descarga devuelven errores como `Blob`. El frontend ahora extrae de forma segura el mensaje JSON del servidor y conserva mensajes de respaldo sin trazas ni detalles internos.
- El 422 de autosave se producía porque la UI creaba enumeraciones vacías como borrador y Spring las rechazaba antes de que el usuario pudiera añadir literales. El contrato de persistencia ahora acepta ese borrador. Tras un 422, el frontend conserva el documento local y memoriza su contenido rechazado para no reenviar automáticamente exactamente el mismo JSON; una edición cambia la huella y vuelve a habilitar autosave.

## Comprobación manual recomendada

1. Crear y guardar un diagrama v2 con `ENTITY`, `CLASS`, `INTERFACE`, `ENUMERATION` y cada tipo de relación; recargar y comprobar geometría, selección, arrastre, nombres y multiplicidades.
2. Exportar XMI, abrirlo en la versión de Enterprise Architect disponible y comprobar clasificadores, miembros y relaciones. Registrar cualquier degradación específica de esa versión; no asumir round-trip universal.
3. Generar backend y fullstack desde un diagrama mixto: la descarga debe conservar el CRUD de entidades y añadir los tipos estructurales bajo `domain/uml` y `models/uml`. La UI debe nombrar estos elementos y advertir que no contienen persistencia ni lógica de negocio.
4. Intentar generar desde un diagrama sin `ENTITY`: debe mostrarse la instrucción de agregar una entidad persistente con primary key.

## Evidencia y límites de interoperabilidad

Al cierre del 24 de octubre de 2026 no existen en este repositorio las rutas obligatorias `docs/qa-samples/EA_referencia.xmi`, `docs/qa-samples/LogicDraft_exportado.xmi` y `docs/qa-samples/fullstackPrueba1.zip`. Por ello no se declara realizada la comparación de regresión contra esas muestras ni la inspección de ese ZIP. Tampoco se ha importado el resultado dentro de una instalación real de Enterprise Architect. Las pruebas automatizadas verifican XML bien formado, estructura UML/EA, referencias y geometría producida por LogicDraft; la reconstrucción visual real continúa pendiente de validación manual.

