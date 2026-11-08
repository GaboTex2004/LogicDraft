import type { TutorialCatalog, TutorialDefinition, TutorialStep } from '../types/tutorial.types.ts'
import { tutorialTargets, type TutorialTargetId } from './targets.ts'

function step(id: string, target: TutorialTargetId, text: string, completion: TutorialStep['completion'], hint: string, binding?: TutorialStep['binding']): TutorialStep {
  return { id, target, text, completion, hint, ...(binding ? { binding } : {}) }
}

const selectNode = step('select-node', 'editor.node', 'Selecciona en el lienzo la clase o entidad elegida para este tutorial.', { kind: 'selected', binding: 'node' }, 'Selecciona el elemento indicado; otro nodo no completa este paso.', 'node')
const properties = step('open-properties', 'editor.properties', 'Abre Propiedades si el panel está cerrado.', { kind: 'control-ready', target: 'node.name' }, 'El panel muestra las propiedades del nodo seleccionado.')
const editors = ['OWNER', 'EDITOR'] as const
const readers = ['OWNER', 'EDITOR', 'VIEWER'] as const
const editorDefaults = {
  contentVersion: 1, domain: 'platform', entryScreens: ['editor'], roles: editors,
  documentVersions: [1, 2], prerequisites: ['open_editor'],
} as const

export const tutorials: readonly TutorialDefinition[] = [
  {
    id: 'choose_workspace', contentVersion: 1, domain: 'platform', title: 'Abrir un workspace',
    examples: ['¿Dónde están mis proyectos?', 'Cómo abro un workspace'], entryScreens: ['dashboard'], roles: readers,
    documentVersions: [], prerequisites: [], inputs: ['workspace'], parameters: [], outputs: [],
    steps: [step('open', 'workspace.open', 'Pulsa Abrir workspace en el espacio que elegiste.', { kind: 'screen', screen: 'projects', scope: 'workspace' }, 'Si hay varios espacios, elige uno antes de iniciar. Para practicar cambios necesitas permiso de edición.', 'workspace')],
  },
  {
    id: 'create_project', contentVersion: 1, domain: 'platform', title: 'Crear un proyecto',
    examples: ['Cómo creo un proyecto', 'Quiero empezar un diagrama nuevo'], entryScreens: ['projects'], roles: editors,
    documentVersions: [], prerequisites: ['choose_workspace'], inputs: ['workspace'], parameters: ['projectName'], outputs: ['project'],
    steps: [
      step('new', 'project.new', 'Pulsa Nuevo proyecto.', { kind: 'control-ready', target: 'project.name' }, 'Se abrirá el formulario de creación.'),
      step('name', 'project.name', 'Escribe el nombre que quieras para tu proyecto.', { kind: 'input-valid', parameter: 'projectName' }, 'Debe tener entre 1 y 100 caracteres. La descripción es opcional.'),
      step('create', 'project.create', 'Pulsa Crear proyecto y espera la confirmación.', { kind: 'created', entity: 'project', capture: 'project' }, 'Un error de la API no completa el paso. El proyecto creado aparecerá en la lista.'),
    ],
  },
  {
    id: 'open_editor', contentVersion: 1, domain: 'platform', title: 'Abrir el editor',
    examples: ['Cómo entro al editor', 'Abrir mi diagrama'], entryScreens: ['projects'], roles: readers,
    documentVersions: [], prerequisites: ['choose_workspace'], inputs: ['workspace', 'project'], parameters: [], outputs: [],
    steps: [step('open', 'project.open', 'Pulsa Abrir en el proyecto elegido.', { kind: 'screen', screen: 'editor', scope: 'project' }, 'Elige un proyecto existente o realiza primero el tutorial Crear un proyecto.', 'project')],
  },
  {
    ...editorDefaults, id: 'create_class', title: 'Crear una clase UML', examples: ['¿Cómo creo una clase?', 'Agregar una clase UML'],
    inputs: ['project'], parameters: [], outputs: ['node'],
    steps: [
      step('tools', 'editor.tools', 'Abre Herramientas si el panel está cerrado.', { kind: 'control-ready', target: 'editor.class' }, 'Busca la sección Elementos.'),
      step('create', 'editor.class', 'Pulsa Clase UML para crear una clase.', { kind: 'created', entity: 'node', capture: 'node', nodeKind: 'CLASS' }, 'Clase UML y Entidad son controles distintos. Este tutorial crea una clase UML.'),
    ],
  },
  {
    ...editorDefaults, id: 'create_entity', title: 'Crear una entidad de datos', examples: ['Cómo creo una entidad', 'Agregar entidad de datos'],
    inputs: ['project'], parameters: [], outputs: ['node'],
    steps: [
      step('tools', 'editor.tools', 'Abre Herramientas si el panel está cerrado.', { kind: 'control-ready', target: 'editor.entity' }, 'Busca la sección Elementos.'),
      step('create', 'editor.entity', 'Pulsa Entidad para crear una entidad de datos.', { kind: 'created', entity: 'node', capture: 'node', nodeKind: 'ENTITY' }, 'Este paso no crea una Clase UML.'),
    ],
  },
  {
    ...editorDefaults, id: 'rename_node', title: 'Cambiar el nombre de una clase o entidad', examples: ['Cómo cambio el nombre de una clase'],
    inputs: ['project', 'node'], parameters: ['nodeName'], outputs: [],
    steps: [selectNode, properties, step('name', 'node.name', 'Cambia el nombre a {{nodeName}}.', { kind: 'field-matches', binding: 'node', field: 'name', parameter: 'nodeName' }, 'Se verifica el nodo elegido por su ID, no otro elemento con el mismo nombre.', 'node')],
  },
  {
    ...editorDefaults, id: 'add_attribute', title: 'Añadir un atributo y elegir su tipo', examples: ['¿Cómo agrego un atributo?', 'Añadir un campo a una clase'],
    inputs: ['project', 'node'], parameters: ['attributeName', 'attributeType'], outputs: ['attribute'],
    steps: [selectNode, properties,
      step('add', 'attribute.add', 'En Atributos, pulsa Agregar.', { kind: 'created', entity: 'attribute', capture: 'attribute' }, 'Se observará el atributo nuevo del nodo seleccionado.', 'node'),
      step('name', 'attribute.name', 'Escribe {{attributeName}} como nombre del atributo.', { kind: 'field-matches', binding: 'attribute', field: 'name', parameter: 'attributeName' }, 'Edita la fila recién creada.', 'attribute'),
      step('type', 'attribute.type', 'Selecciona {{attributeType}} como tipo.', { kind: 'field-matches', binding: 'attribute', field: 'type', parameter: 'attributeType' }, 'El editor utiliza tipos como INTEGER y VARCHAR; no escribas un tipo inexistente.', 'attribute'),
    ],
  },
  {
    ...editorDefaults, id: 'set_primary_key', title: 'Marcar una clave primaria', examples: ['Cómo marco una clave primaria', 'Dónde está Primary Key'],
    inputs: ['project', 'node', 'attribute'], parameters: [], outputs: [],
    steps: [selectNode, properties, step('pk', 'attribute.pk', 'Marca Primary Key en el atributo elegido.', { kind: 'primary-key-enabled', binding: 'attribute' }, 'Si el atributo es estructural o está bloqueado por otro colaborador, este tutorial debe pausarse.', 'attribute')],
  },
  {
    ...editorDefaults, id: 'create_relationship', title: 'Relacionar dos clases o entidades', examples: ['Cómo conecto dos clases', 'Crear una asociación'],
    inputs: ['project', 'sourceNode', 'targetNode'], parameters: [], outputs: ['relationship'],
    steps: [
      step('tools', 'editor.tools', 'Abre Herramientas si el panel está cerrado.', { kind: 'control-ready', target: 'relation.mode' }, 'Los dos nodos deben existir y ser distintos.'),
      step('mode', 'relation.mode', 'Selecciona ASSOCIATION en Relaciones UML.', { kind: 'relation-kind', value: 'ASSOCIATION' }, 'Este tutorial enseña una asociación, no herencia ni composición.'),
      step('connect', 'relation.connect', 'Arrastra un conector del nodo de origen hasta el nodo de destino elegido.', { kind: 'created', entity: 'relationship', capture: 'relationship' }, 'Se comprobarán ambos extremos por ID. Mover el nodo no crea una relación.', 'sourceNode'),
    ],
  },
  {
    ...editorDefaults, id: 'configure_cardinality', title: 'Configurar la cardinalidad', examples: ['Cómo cambio la cardinalidad', 'Dónde pongo uno a muchos'],
    inputs: ['project', 'relationship'], parameters: ['sourceCardinality', 'targetCardinality'], outputs: [],
    steps: [
      step('select', 'relation.select', 'Selecciona la relación que quieres configurar.', { kind: 'selected', binding: 'relationship' }, 'No selecciones otra relación entre los mismos nodos.', 'relationship'),
      step('properties', 'editor.properties', 'Abre Propiedades si el panel está cerrado.', { kind: 'control-ready', target: 'relation.source' }, 'Los extremos se muestran como origen y destino.'),
      step('source', 'relation.source', 'En Cardinalidad origen, elige {{sourceCardinality}}.', { kind: 'field-matches', binding: 'relationship', field: 'sourceCardinality', parameter: 'sourceCardinality' }, 'Las etiquetas de cardinalidad se toman del editor.', 'relationship'),
      step('target', 'relation.target', 'En Cardinalidad destino, elige {{targetCardinality}}.', { kind: 'field-matches', binding: 'relationship', field: 'targetCardinality', parameter: 'targetCardinality' }, 'Las relaciones estructurales y sin multiplicidad necesitan otra lección; no forzar controles deshabilitados.', 'relationship'),
    ],
  },
  {
    ...editorDefaults, id: 'verify_save', title: 'Comprobar el guardado', examples: ['Cómo guardo mi diagrama', 'Cómo sé si se guardó'],
    inputs: ['project'], parameters: [], outputs: [],
    steps: [step('save', 'diagram.save', 'LogicDraft guarda automáticamente. Puedes pulsar Guardar y esperar la confirmación.', { kind: 'saved-revision', scope: 'project', require: 'all-local-changes-acknowledged' }, 'Se espera confirmación del servidor para tus cambios. Un conflicto, error o guardado de otro usuario no completa este paso.')],
  },
]

export const tutorialCatalog: TutorialCatalog = {
  version: 1, locale: 'es', runtimeAvailable: true, targets: tutorialTargets, tutorials,
}
