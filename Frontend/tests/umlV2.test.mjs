import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { validateDiagramDocument } from "../src/features/diagram/services/diagramDocumentValidation.ts";
import {
  applyDiagramOperations,
  parseDiagramAiResponse,
} from "../src/features/diagram/services/applyDiagramOperations.ts";

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
            type: "ADD_ATTRIBUTE",
            entityName: "EstadoVenta",
            attribute: { name: "codigo", dataType: "String", primaryKey: false, nullable: true },
          },
        ],
      ),
    /ENUMERATION/,
  );

  // Fase 2: ENUMERATION and INTERFACE can be deleted via DELETE_ENTITY and mutated with their own UML v2 operations
  const afterEnumLiteralAndDelete = applyDiagramOperations(
    [producto, contrato, estado],
    [],
    [
      {
        type: "ADD_ENUM_LITERAL",
        entityName: "EstadoVenta",
        literal: "ANULADA",
      },
      {
        type: "DELETE_ENTITY",
        entityName: "Exportable",
      },
    ],
    () => "op-id",
  );
  assert.equal(afterEnumLiteralAndDelete.nodes.length, 2);
  assert.deepEqual(
    afterEnumLiteralAndDelete.nodes.find((n) => n.data.name === "EstadoVenta").data.literals,
    ["PENDIENTE", "PAGADA", "ANULADA"],
  );
});

test("Scenarios 13-39: Full UML v2 CRUD operations (classifiers, attributes, methods, parameters, literals, relationships, and multi-op B6)", () => {
  let seq = 0;
  const nextId = () => `id-${++seq}`;

  // 13-20: Classifiers (ENTITY, CLASS, INTERFACE, ENUMERATION, ASSOCIATION_CLASS, RENAME_ENTITY, UPDATE_CLASSIFIER, DELETE_ENTITY)
  // 21-24: Attributes (ADD, RENAME, CHANGE_TYPE, SET_VISIBILITY, SET_STATIC, SET_PRIMARY_KEY, SET_NULLABLE, DELETE)
  // 25-27: Methods (ADD, UPDATE, DELETE)
  // 28-30: Parameters (ADD, UPDATE, DELETE)
  // 31-33: Enum literals (ADD, RENAME, DELETE)
  // 34-36: Relationships (ASSOCIATION, GENERALIZATION, REALIZATION, AGGREGATION, COMPOSITION, DEPENDENCY + UPDATE + DELETE)
  const batch1 = parseDiagramAiResponse({
    operations: [
      {
        type: "ADD_ENTITY",
        entity: {
          name: "BaseAuditable",
          kind: "CLASS",
          abstract: true,
          attributes: [{ name: "id", dataType: "Integer", primaryKey: true, nullable: false }],
        },
      },
      {
        type: "ADD_ENTITY",
        entity: {
          name: "Producto",
          kind: "CLASS",
          attributes: [
            { name: "id", dataType: "Integer", primaryKey: true, nullable: false },
            { name: "Precio de venta", dataType: "Double", primaryKey: false, nullable: false, visibility: "private" },
          ],
          methods: [
            {
              name: "calcularSubtotal",
              returnType: "Double",
              visibility: "public",
              parameters: [{ name: "cantidad", type: "Integer" }],
            },
          ],
        },
      },
      {
        type: "ADD_ENTITY",
        entity: {
          name: "Venta",
          kind: "ENTITY",
          attributes: [{ name: "id", dataType: "Integer", primaryKey: true, nullable: false }],
        },
      },
      {
        type: "ADD_ENTITY",
        entity: {
          name: "Exportable",
          kind: "INTERFACE",
          methods: [{ name: "exportar", returnType: "String", visibility: "public", parameters: [] }],
        },
      },
      {
        type: "ADD_ENTITY",
        entity: {
          name: "EstadoVenta",
          kind: "ENUMERATION",
          literals: ["PENDIENTE", "PAGADA"],
        },
      },
      {
        type: "CREATE_ASSOCIATION",
        association: {
          sourceEntity: "Venta",
          targetEntity: "Producto",
          associationEntityName: "compra",
          attributes: [{ name: "descuento", dataType: "Double", primaryKey: false, nullable: true }],
        },
      },
      {
        type: "UPDATE_CLASSIFIER",
        entityName: "Producto",
        kind: "CLASS",
        abstract: false,
      },
      // Attribute CRUD
      {
        type: "ADD_ATTRIBUTE",
        entityName: "Producto",
        attribute: { name: "stock", dataType: "Integer", primaryKey: false, nullable: true, visibility: "private" },
      },
      {
        type: "RENAME_ATTRIBUTE",
        entityName: "Producto",
        attributeName: "stock",
        newName: "stockActual",
      },
      {
        type: "CHANGE_ATTRIBUTE_TYPE",
        entityName: "Producto",
        attributeName: "stockActual",
        dataType: "Long",
      },
      {
        type: "SET_ATTRIBUTE_VISIBILITY",
        entityName: "Producto",
        attributeName: "stockActual",
        visibility: "protected",
      },
      {
        type: "SET_ATTRIBUTE_STATIC",
        entityName: "Producto",
        attributeName: "stockActual",
        value: true,
      },
      {
        type: "SET_ATTRIBUTE_NULLABLE",
        entityName: "Producto",
        attributeName: "stockActual",
        value: false,
      },
      {
        type: "DELETE_ATTRIBUTE",
        entityName: "Producto",
        attributeName: "stockActual",
      },
      // Method & Parameter CRUD
      {
        type: "ADD_METHOD",
        entityName: "Producto",
        method: {
          name: "aplicarImpuesto",
          returnType: "Double",
          visibility: "public",
          parameters: [{ name: "tasa", type: "Double" }],
        },
      },
      {
        type: "ADD_METHOD_PARAMETER",
        entityName: "Producto",
        methodName: "aplicarImpuesto",
        parameter: { name: "redondeo", type: "Boolean" },
      },
      {
        type: "UPDATE_METHOD_PARAMETER",
        entityName: "Producto",
        methodName: "aplicarImpuesto",
        parameterName: "redondeo",
        newName: "conRedondeo",
        newType: "String",
      },
      {
        type: "DELETE_METHOD_PARAMETER",
        entityName: "Producto",
        methodName: "aplicarImpuesto",
        parameterName: "conRedondeo",
      },
      {
        type: "UPDATE_METHOD",
        entityName: "Producto",
        methodName: "aplicarImpuesto",
        newName: "calcularImpuesto",
        returnType: "BigDecimal",
        visibility: "protected",
      },
      {
        type: "DELETE_METHOD",
        entityName: "Producto",
        methodName: "calcularImpuesto",
      },
      // Enum Literal CRUD
      {
        type: "ADD_ENUM_LITERAL",
        entityName: "EstadoVenta",
        literal: "CANCELADA",
      },
      {
        type: "RENAME_ENUM_LITERAL",
        entityName: "EstadoVenta",
        literal: "CANCELADA",
        newLiteral: "ANULADA",
      },
      {
        type: "DELETE_ENUM_LITERAL",
        entityName: "EstadoVenta",
        literal: "ANULADA",
      },
      // Relationship CRUD (all 6 UML kinds)
      {
        type: "ADD_RELATIONSHIP",
        relationship: {
          sourceEntity: "Producto",
          targetEntity: "BaseAuditable",
          kind: "GENERALIZATION",
        },
      },
      {
        type: "ADD_RELATIONSHIP",
        relationship: {
          sourceEntity: "Producto",
          targetEntity: "Exportable",
          kind: "REALIZATION",
        },
      },
      {
        type: "ADD_RELATIONSHIP",
        relationship: {
          sourceEntity: "Venta",
          targetEntity: "EstadoVenta",
          kind: "DEPENDENCY",
          name: "usaEstado",
        },
      },
      {
        type: "ADD_RELATIONSHIP",
        relationship: {
          sourceEntity: "Venta",
          targetEntity: "BaseAuditable",
          kind: "AGGREGATION",
          sourceCardinality: "ONE_ONE",
          targetCardinality: "ZERO_MANY",
          aggregationEnd: "SOURCE",
          name: "auditoria",
        },
      },
      {
        type: "UPDATE_RELATIONSHIP",
        relationship: {
          sourceEntity: "Venta",
          targetEntity: "BaseAuditable",
          kind: "COMPOSITION",
          sourceCardinality: "ONE_ONE",
          targetCardinality: "ONE_MANY",
          aggregationEnd: "SOURCE",
          name: "auditoriaFuerte",
        },
      },
      {
        type: "DELETE_RELATIONSHIP",
        relationship: {
          sourceEntity: "Venta",
          targetEntity: "BaseAuditable",
          kind: "COMPOSITION",
        },
      },
      // B6: Multi-operation atomic execution
      {
        type: "DELETE_ATTRIBUTE",
        entityName: "Producto",
        attributeName: "Precio de venta",
      },
      {
        type: "ADD_ATTRIBUTE",
        entityName: "compra",
        attribute: { name: "CANTIDAD", dataType: "Integer", primaryKey: false, nullable: true },
      },
    ],
  });

  const result = applyDiagramOperations([], [], batch1, nextId);
  const prod = result.nodes.find((n) => n.data.name === "Producto");
  const compra = result.nodes.find((n) => n.data.name === "compra");
  assert.ok(prod);
  assert.ok(compra);
  assert.equal(
    prod.data.attributes.some((a) => a.name.toLowerCase() === "precio de venta"),
    false,
  );
  assert.equal(
    compra.data.attributes.some((a) => a.name === "CANTIDAD" && a.type === "INTEGER"),
    true,
  );

  // Scenario 37 & 38: Block structural FK attribute deletion and structural edge deletion on ASSOCIATION_CLASS
  assert.throws(
    () =>
      applyDiagramOperations(
        result.nodes,
        result.edges,
        [
          {
            type: "DELETE_ATTRIBUTE",
            entityName: "compra",
            attributeName: "venta_id",
          },
        ],
        nextId,
      ),
    /estructural|asociativa/i,
  );
  assert.throws(
    () =>
      applyDiagramOperations(
        result.nodes,
        result.edges,
        [
          {
            type: "DELETE_RELATIONSHIP",
            relationship: {
              sourceEntity: "Venta",
              targetEntity: "compra",
            },
          },
        ],
        nextId,
      ),
    /estructural|asociativa/i,
  );
});
