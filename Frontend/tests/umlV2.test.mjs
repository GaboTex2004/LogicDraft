import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { validateDiagramDocument } from "../src/features/diagram/services/diagramDocumentValidation.ts";
import { applyDiagramOperations } from "../src/features/diagram/services/applyDiagramOperations.ts";

const node = (id, name, kind, methods = []) => ({
  id,
  type: "entity",
  position: { x: 0, y: 0 },
  data: { id, name, kind, attributes: [], methods },
});
const method = (id, name) => ({
  id,
  name,
  visibility: "public",
  returnType: "void",
  parameters: [{ id: `${id}-p`, name: "value", type: "String" }],
});

test("UML v2 preserves stable method and parameter IDs and validates realization", () => {
  const document = {
    version: 2,
    nodes: [
      node("service", "EmailService", "CLASS", [method("send", "send")]),
      node("contract", "Notificable", "INTERFACE", [
        method("notify", "notify"),
      ]),
    ],
    edges: [
      {
        id: "impl",
        source: "service",
        target: "contract",
        data: {
          sourceCardinality: "ONE_ONE",
          targetCardinality: "ONE_ONE",
          relationKind: "REALIZATION",
        },
      },
    ],
  };
  assert.doesNotThrow(() => validateDiagramDocument(document));
  assert.equal(document.nodes[0].data.methods[0].parameters[0].id, "send-p");
});

test("UML v2 rejects duplicate method IDs and realization towards a class", () => {
  assert.throws(() =>
    validateDiagramDocument({
      version: 2,
      nodes: [
        node("a", "A", "CLASS", [method("same", "one"), method("same", "two")]),
      ],
      edges: [],
    }),
  );
  assert.throws(() =>
    validateDiagramDocument({
      version: 2,
      nodes: [node("a", "A", "CLASS"), node("b", "B", "CLASS")],
      edges: [
        {
          id: "bad",
          source: "a",
          target: "b",
          data: {
            sourceCardinality: "ONE_ONE",
            targetCardinality: "ONE_ONE",
            relationKind: "REALIZATION",
          },
        },
      ],
    }),
  );
});

test("UML relationship renderer contains the required notation markers", async () => {
  const source = await readFile(
    new URL(
      "../src/features/diagram/components/RelationshipEdge.tsx",
      import.meta.url,
    ),
    "utf8",
  );
  assert.match(source, /uml-hollow-triangle/);
  assert.match(source, /uml-hollow-diamond/);
  assert.match(source, /uml-solid-diamond/);
  assert.match(source, /uml-open-arrow/);
  assert.match(source, /strokeDasharray/);
});

test("generator UX reports generated non-persistent UML skeletons", async () => {
  const source = await readFile(
    new URL(
      "../src/features/diagram/pages/DiagramEditorPage.tsx",
      import.meta.url,
    ),
    "utf8",
  );
  assert.match(
    source,
    /Se añadieron tipos UML estructurales sin persistencia ni lógica de negocio/,
  );
  assert.match(source, /responseMessage\(requestError\)/);
});

test("UML v2 distinguishes enumeration literals and validates relation direction", () => {
  const enumeration = node("state", "Estado", "ENUMERATION");
  enumeration.data.literals = ["ACTIVO"];
  enumeration.data.attributes = [
    { id: "wrong", name: "ACTIVO", type: "VARCHAR", primaryKey: false },
  ];
  assert.throws(
    () =>
      validateDiagramDocument({ version: 2, nodes: [enumeration], edges: [] }),
    /literales, no atributos/,
  );

  const contract = node("contract", "Contrato", "INTERFACE");
  const implementation = node("impl", "Implementacion", "CLASS");
  assert.throws(
    () =>
      validateDiagramDocument({
        version: 2,
        nodes: [contract, implementation],
        edges: [
          {
            id: "wrong-direction",
            source: "contract",
            target: "impl",
            data: {
              sourceCardinality: "ONE_ONE",
              targetCardinality: "ONE_ONE",
              relationKind: "REALIZATION",
            },
          },
        ],
      }),
    /partir de una clase/,
  );
});

test("new empty enumeration is a valid persistable draft", () => {
  const enumeration = node("enum", "Nueva enumeración", "ENUMERATION");
  enumeration.data.literals = [];
  assert.doesNotThrow(() =>
    validateDiagramDocument({ version: 2, nodes: [enumeration], edges: [] }),
  );
});

test("autosave remembers a rejected document and only retries automatically after a change", async () => {
  const source = await readFile(
    new URL(
      "../src/features/diagram/pages/DiagramEditorPage.tsx",
      import.meta.url,
    ),
    "utf8",
  );
  assert.match(source, /rejectedDocumentRef\.current === documentFingerprint/);
  assert.match(source, /rejectedDocumentRef\.current = documentFingerprint/);
  assert.match(source, /handleSave\(true\)/);
  assert.match(source, /\.\.\.method,/);
  assert.match(source, /attributes: node\.data\.attributes\.map\(\(attribute\) => \(\{ \.\.\.attribute \}\)\)/);
  assert.match(source, /\.\.\.saved\.contenido/);
});

test("UML v2 detects invalid class-to-interface aggregation before autosave", () => {
  const nodes = [
    node("class", "Servicio", "CLASS"),
    node("interface", "Notificable", "INTERFACE"),
  ];
  assert.throws(
    () =>
      validateDiagramDocument({
        version: 2,
        nodes,
        edges: [
          {
            id: "bad-aggregation",
            source: "class",
            target: "interface",
            data: {
              relationKind: "AGGREGATION",
              sourceCardinality: "ONE_ONE",
              targetCardinality: "ONE_ONE",
            },
          },
        ],
      }),
    /agregación o composición/,
  );
});

test("the diagram editor rejects incompatible UML edges before publishing or autosaving", async () => {
  const source = await readFile(
    new URL(
      "../src/features/diagram/pages/DiagramEditorPage.tsx",
      import.meta.url,
    ),
    "utf8",
  );
  assert.match(
    source,
    /validateDiagramDocument\(\s*serializeDocument\(nodesRef\.current, \[\.\.\.edgesRef\.current, edge\]\)/,
  );
  assert.match(
    source,
    /uniqueDefaultName\(\s*baseName,\s*nodesRef\.current\.map\(\(node\) => node\.data\.name\)/,
  );
  assert.match(source, /validateDiagramDocument\(document\)/);
});

test("UML v2 AI operations preserve CLASS kind and reject mutations on INTERFACE and ENUMERATION", () => {
  const producto = node("entity-prod", "Producto", "CLASS", [method("m-1", "calcular")]);
  producto.data.attributes = [
    { id: "attr-id", name: "ID", type: "INTEGER", primaryKey: true, nullable: false },
  ];
  const contrato = node("entity-iface", "Exportable", "INTERFACE", [method("m-2", "exportar")]);
  const estado = node("entity-enum", "EstadoVenta", "ENUMERATION");
  estado.data.literals = ["PENDIENTE", "PAGADA"];

  const applied = applyDiagramOperations(
    [producto, contrato, estado],
    [],
    [
      {
        type: "ADD_ATTRIBUTE",
        entityName: "Producto",
        attribute: { name: "precio", dataType: "Double", primaryKey: false, nullable: false },
      },
    ],
    () => "new-id",
  );
  assert.equal(applied.nodes[0].data.kind, "CLASS");
  assert.equal(applied.nodes[0].data.methods.length, 1);
  assert.equal(applied.nodes[0].data.attributes.length, 2);

  assert.throws(
    () =>
      applyDiagramOperations(
        [producto, contrato, estado],
        [],
        [
          {
            type: "ADD_ATTRIBUTE",
            entityName: "Exportable",
            attribute: { name: "codigo", dataType: "String", primaryKey: false, nullable: true },
          },
        ],
      ),
    /INTERFACE/,
  );
  assert.throws(
    () =>
      applyDiagramOperations(
        [producto, contrato, estado],
        [],
        [
          {
            type: "DELETE_ENTITY",
            entityName: "EstadoVenta",
          },
        ],
      ),
    /ENUMERATION/,
  );
});
