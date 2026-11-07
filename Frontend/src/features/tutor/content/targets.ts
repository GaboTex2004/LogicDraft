import type { TutorialTarget } from '../types/tutorial.types.ts'

const diagram = 'src/features/diagram/components/'
const properties = `${diagram}DiagramPropertiesPanel.tsx`

/** Inventory audited against current UI. Registration/highlighting is NOT implemented. */
export const tutorialTargets = {
  'workspace.open': { screen: 'dashboard', sourceFile: 'src/features/dashboard/components/WorkspaceCard.tsx', control: 'Abrir workspace', instance: 'workspace', integration: 'pending' },
  'project.new': { screen: 'projects', sourceFile: 'src/features/project/pages/ProjectsPage.tsx', control: 'Nuevo proyecto', integration: 'pending' },
  'project.name': { screen: 'projects', sourceFile: 'src/features/project/pages/ProjectsPage.tsx', control: 'Nombre / project-name', integration: 'pending' },
  'project.create': { screen: 'projects', sourceFile: 'src/features/project/pages/ProjectsPage.tsx', control: 'Crear proyecto', integration: 'pending' },
  'project.open': { screen: 'projects', sourceFile: 'src/features/project/components/ProjectCard.tsx', control: 'Abrir', instance: 'project', integration: 'pending' },
  'editor.tools': { screen: 'editor', sourceFile: `${diagram}DiagramToolbar.tsx`, control: 'Herramientas', integration: 'pending' },
  'editor.class': { screen: 'editor', sourceFile: `${diagram}DiagramSidebar.tsx`, control: 'Clase UML / onAddNode(CLASS)', integration: 'pending' },
  'editor.entity': { screen: 'editor', sourceFile: `${diagram}DiagramSidebar.tsx`, control: 'Entidad / onAddNode(ENTITY)', integration: 'pending' },
  'editor.node': { screen: 'editor', sourceFile: `${diagram}EntityNode.tsx`, control: 'Nodo del lienzo', instance: 'node', integration: 'pending' },
  'editor.properties': { screen: 'editor', sourceFile: `${diagram}DiagramToolbar.tsx`, control: 'Propiedades', integration: 'pending' },
  'node.name': { screen: 'editor', sourceFile: properties, control: 'Nombre / entity-name', instance: 'node', integration: 'pending' },
  'attribute.add': { screen: 'editor', sourceFile: properties, control: 'Atributos / ＋ Agregar', instance: 'node', integration: 'pending' },
  'attribute.name': { screen: 'editor', sourceFile: properties, control: 'Nombre del atributo', instance: 'attribute', integration: 'pending' },
  'attribute.type': { screen: 'editor', sourceFile: properties, control: 'Tipo de atributo', instance: 'attribute', integration: 'pending' },
  'attribute.pk': { screen: 'editor', sourceFile: properties, control: 'Primary Key', instance: 'attribute', integration: 'pending' },
  'relation.mode': { screen: 'editor', sourceFile: `${diagram}DiagramSidebar.tsx`, control: 'Relaciones UML / ASSOCIATION', integration: 'pending' },
  'relation.connect': { screen: 'editor', sourceFile: 'src/features/diagram/pages/DiagramEditorPage.tsx', control: 'ReactFlow onConnect + handles EntityNode', instance: 'node', integration: 'pending' },
  'relation.select': { screen: 'editor', sourceFile: `${diagram}RelationshipEdge.tsx`, control: 'Arista del lienzo', instance: 'relationship', integration: 'pending' },
  'relation.source': { screen: 'editor', sourceFile: properties, control: 'Cardinalidad origen', instance: 'relationship', integration: 'pending' },
  'relation.target': { screen: 'editor', sourceFile: properties, control: 'Cardinalidad destino', instance: 'relationship', integration: 'pending' },
  'diagram.save': { screen: 'editor', sourceFile: `${diagram}DiagramToolbar.tsx`, control: 'Guardar y estado de guardado', integration: 'pending' },
} as const satisfies Record<string, TutorialTarget>

export type TutorialTargetId = keyof typeof tutorialTargets
