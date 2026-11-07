import type { TutorialJourney } from '../types/tutorial.types.ts'

/** Fixed exercise. Slots are not database IDs; E3 must bind local action results. */
export const firstDiagram: TutorialJourney = {
  id: 'first_diagram', catalogVersion: 1, title: 'Crea tu primer diagrama',
  items: [
    { id: 'workspace', tutorialId: 'choose_workspace', parameters: {}, inputs: { workspace: 'chosenWorkspace' }, outputs: {} },
    { id: 'project', tutorialId: 'create_project', parameters: { projectName: 'Mi primer diagrama' }, inputs: { workspace: 'chosenWorkspace' }, outputs: { project: 'lessonProject' } },
    { id: 'editor', tutorialId: 'open_editor', parameters: {}, inputs: { workspace: 'chosenWorkspace', project: 'lessonProject' }, outputs: {} },
    { id: 'client', tutorialId: 'create_class', parameters: {}, inputs: { project: 'lessonProject' }, outputs: { node: 'client' } },
    { id: 'client-name', tutorialId: 'rename_node', parameters: { nodeName: 'Cliente' }, inputs: { project: 'lessonProject', node: 'client' }, outputs: {} },
    { id: 'client-id', tutorialId: 'add_attribute', parameters: { attributeName: 'id', attributeType: 'INTEGER' }, inputs: { project: 'lessonProject', node: 'client' }, outputs: { attribute: 'clientId' } },
    { id: 'client-pk', tutorialId: 'set_primary_key', parameters: {}, inputs: { project: 'lessonProject', node: 'client', attribute: 'clientId' }, outputs: {} },
    { id: 'client-display-name', tutorialId: 'add_attribute', parameters: { attributeName: 'nombre', attributeType: 'VARCHAR' }, inputs: { project: 'lessonProject', node: 'client' }, outputs: { attribute: 'clientName' } },
    { id: 'order', tutorialId: 'create_class', parameters: {}, inputs: { project: 'lessonProject' }, outputs: { node: 'order' } },
    { id: 'order-name', tutorialId: 'rename_node', parameters: { nodeName: 'Pedido' }, inputs: { project: 'lessonProject', node: 'order' }, outputs: {} },
    { id: 'association', tutorialId: 'create_relationship', parameters: {}, inputs: { project: 'lessonProject', sourceNode: 'client', targetNode: 'order' }, outputs: { relationship: 'clientOrders' } },
    { id: 'cardinality', tutorialId: 'configure_cardinality', parameters: { sourceCardinality: 'ONE_ONE', targetCardinality: 'ZERO_MANY' }, inputs: { project: 'lessonProject', relationship: 'clientOrders' }, outputs: {} },
    { id: 'save', tutorialId: 'verify_save', parameters: {}, inputs: { project: 'lessonProject' }, outputs: {} },
  ],
}
