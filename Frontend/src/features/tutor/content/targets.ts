import type { TutorialTarget } from '../types/tutorial.types.ts'

const diagram = 'src/features/diagram/components/'
const properties = `${diagram}DiagramPropertiesPanel.tsx`

/** E3.2: registered refs; tutorial execution/evidence remains pending in E3.3/E3.4. */
export const tutorialTargets = {
  'workspace.open': { screen: 'dashboard', sourceFile: 'src/features/dashboard/components/WorkspaceCard.tsx', control: 'Abrir workspace', instance: 'workspace', integration: 'registered' },
  'project.new': { screen: 'projects', sourceFile: 'src/features/project/pages/ProjectsPage.tsx', control: 'Nuevo proyecto', integration: 'registered' },
  'project.name': { screen: 'projects', sourceFile: 'src/features/project/pages/ProjectsPage.tsx', control: 'Nombre / project-name', integration: 'registered' },
  'project.create': { screen: 'projects', sourceFile: 'src/features/project/pages/ProjectsPage.tsx', control: 'Crear proyecto', integration: 'registered' },
  'project.open': { screen: 'projects', sourceFile: 'src/features/project/components/ProjectCard.tsx', control: 'Abrir', instance: 'project', integration: 'registered' },
  'editor.tools': { screen: 'editor', sourceFile: `${diagram}DiagramToolbar.tsx`, control: 'Herramientas', integration: 'registered' },
  'editor.class': { screen: 'editor', sourceFile: `${diagram}DiagramSidebar.tsx`, control: 'Clase UML / onAddNode(CLASS)', integration: 'registered' },
  'editor.entity': { screen: 'editor', sourceFile: `${diagram}DiagramSidebar.tsx`, control: 'Entidad / onAddNode(ENTITY)', integration: 'registered' },
  'editor.node': { screen: 'editor', sourceFile: `${diagram}EntityNode.tsx`, control: 'Nodo del lienzo', instance: 'node', integration: 'registered' },
  'editor.properties': { screen: 'editor', sourceFile: `${diagram}DiagramToolbar.tsx`, control: 'Propiedades', integration: 'registered' },
  'node.name': { screen: 'editor', sourceFile: properties, control: 'Nombre / entity-name', instance: 'node', integration: 'registered' },
  'attribute.add': { screen: 'editor', sourceFile: properties, control: 'Atributos / ＋ Agregar', instance: 'node', integration: 'registered' },
  'attribute.name': { screen: 'editor', sourceFile: properties, control: 'Nombre del atributo', instance: 'attribute', integration: 'registered' },
  'attribute.type': { screen: 'editor', sourceFile: properties, control: 'Tipo de atributo', instance: 'attribute', integration: 'registered' },
  'attribute.pk': { screen: 'editor', sourceFile: properties, control: 'Primary Key', instance: 'attribute', integration: 'registered' },
  'relation.mode': { screen: 'editor', sourceFile: `${diagram}DiagramSidebar.tsx`, control: 'Relaciones UML / ASSOCIATION', integration: 'registered' },
  'relation.connect': { screen: 'editor', sourceFile: 'src/features/diagram/components/EntityNode.tsx', control: 'Conector de origen derecho del nodo', instance: 'node', integration: 'registered' },
  'relation.select': { screen: 'editor', sourceFile: `${diagram}RelationshipEdge.tsx`, control: 'Arista del lienzo', instance: 'relationship', integration: 'registered' },
  'relation.source': { screen: 'editor', sourceFile: properties, control: 'Cardinalidad origen', instance: 'relationship', integration: 'registered' },
  'relation.target': { screen: 'editor', sourceFile: properties, control: 'Cardinalidad destino', instance: 'relationship', integration: 'registered' },
  'diagram.save': { screen: 'editor', sourceFile: `${diagram}DiagramToolbar.tsx`, control: 'Guardar y estado de guardado', integration: 'registered' },
} as const satisfies Record<string, TutorialTarget>

export type TutorialTargetId = keyof typeof tutorialTargets
