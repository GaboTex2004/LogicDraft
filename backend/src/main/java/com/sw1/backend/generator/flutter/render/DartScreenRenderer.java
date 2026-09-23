package com.sw1.backend.generator.flutter.render;

import com.sw1.backend.generator.flutter.FlutterContract;
import com.sw1.backend.generator.flutter.FlutterContract.Field;
import com.sw1.backend.generator.flutter.FlutterTypeMapper;
import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.CanonicalType;
import com.sw1.backend.generator.spring.OwnedRelation;
import com.sw1.backend.generator.spring.SpringNames;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DartScreenRenderer {
    private DartScreenRenderer() {
    }

    public static String list(ApplicationEntity entity, List<OwnedRelation> relations, String fileName) {
        String type = entity.technicalName();
        String pk = FlutterContract.primaryKey(entity).technicalName();
        Field visible = FlutterContract.responseFields(entity, List.of()).stream()
                .filter(field -> field.type() == CanonicalType.STRING && field.name().equalsIgnoreCase("nombre"))
                .findFirst().orElse(null);
        String idTitle = "'" + escape(entity.name()) + " #${item." + pk + "}'";
        String title = visible == null ? idTitle
                : "item." + visible.name() + (visible.nullable() ? " ?? " + idTitle : "");
        String details = details(entity, relations, pk, visible);
        return """
                import 'package:flutter/material.dart';

                import '../../core/network/api_client.dart';
                import '../../models/%s.dart';
                import '../../services/%s_service.dart';
                import '%s_form_screen.dart';

                class %sListScreen extends StatefulWidget {
                  const %sListScreen({super.key});

                  @override
                  State<%sListScreen> createState() => _%sListScreenState();
                }

                class _%sListScreenState extends State<%sListScreen> {
                  final _service = const %sService();
                  List<%s> _items = const [];
                  bool _loading = true;
                  String? _error;

                  @override
                  void initState() {
                    super.initState();
                    _load();
                  }

                  Future<void> _load() async {
                    setState(() { _loading = true; _error = null; });
                    try {
                      final items = await _service.list();
                      if (mounted) setState(() => _items = items);
                    } on ApiException catch (error) {
                      if (mounted) setState(() => _error = error.message);
                    } finally {
                      if (mounted) setState(() => _loading = false);
                    }
                  }

                  Future<void> _openForm([%s? item]) async {
                    final editing = item != null;
                    final changed = await Navigator.of(context).push<bool>(MaterialPageRoute(
                      builder: (_) => %sFormScreen(initial: item),
                    ));
                    if (changed == true && mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                        content: Text(editing ? 'Registro actualizado correctamente' : 'Registro guardado correctamente'),
                      ));
                      await _load();
                    }
                  }

                  Future<void> _delete(%s item) async {
                    final confirmed = await showDialog<bool>(
                      context: context,
                      builder: (context) => AlertDialog(
                        title: const Text('Eliminar registro'),
                        content: const Text('¿Seguro que desea eliminar este registro?'),
                        actions: [
                          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancelar')),
                          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Eliminar')),
                        ],
                      ),
                    );
                    if (confirmed != true) return;
                    try {
                      await _service.delete(item.%s);
                      if (!mounted) return;
                      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Registro eliminado')));
                      await _load();
                    } on ApiException catch (error) {
                      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Error al eliminar: ${error.message}')));
                    }
                  }

                  @override
                  Widget build(BuildContext context) => Scaffold(
                    appBar: AppBar(title: const Text('%s')),
                    body: LayoutBuilder(
                      builder: (context, constraints) => Center(
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 960),
                          child: RefreshIndicator(
                            onRefresh: _load,
                            child: _loading
                          ? const Center(child: CircularProgressIndicator())
                          : _error != null
                              ? ListView(children: [
                                  Padding(padding: const EdgeInsets.all(24), child: Column(children: [
                                    Text(_error!),
                                    const SizedBox(height: 12),
                                    FilledButton(onPressed: _load, child: const Text('Reintentar')),
                                  ])),
                                ])
                              : _items.isEmpty
                                  ? ListView(children: [Padding(
                                      padding: const EdgeInsets.all(32),
                                      child: Column(children: [
                                        const Icon(Icons.inbox_outlined, size: 48),
                                        const SizedBox(height: 12),
                                        const Text('No hay registros de %s.'),
                                        const SizedBox(height: 16),
                                        FilledButton.icon(
                                          onPressed: _openForm,
                                          icon: const Icon(Icons.add),
                                          label: const Text('Registrar %s'),
                                        ),
                                      ]),
                                    )])
                                  : ListView.builder(
                                      itemCount: _items.length,
                                      itemBuilder: (_, index) {
                                        final item = _items[index];
                                        return Card(
                                          margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                                          child: ListTile(
                                            title: Text(%s),
                                            subtitle: Text(%s),
                                            onTap: () => _openForm(item),
                                            trailing: Wrap(spacing: 4, children: [
                                              IconButton(tooltip: 'Editar', icon: const Icon(Icons.edit_outlined), onPressed: () => _openForm(item)),
                                              IconButton(tooltip: 'Eliminar', icon: const Icon(Icons.delete_outline), onPressed: () => _delete(item)),
                                            ]),
                                          ),
                                        );
                                      },
                                    ),
                          ),
                        ),
                      ),
                    ),
                    floatingActionButton: FloatingActionButton.extended(
                      onPressed: _openForm,
                      icon: const Icon(Icons.add),
                      label: const Text('Registrar %s'),
                    ),
                  );
                }
                """.formatted(fileName, fileName, fileName, type, type, type, type, type, type, type,
                type, type, type, type, pk, entity.name(), entity.name(), entity.name(),
                title, details, entity.name());
    }

    private static String details(ApplicationEntity entity, List<OwnedRelation> relations, String pk, Field visible) {
        StringBuilder result = new StringBuilder("<String>['ID: ${item.").append(pk).append("}'");
        for (var field : entity.fields()) {
            if (field.primaryKey() || visible != null && field.technicalName().equals(visible.name())) continue;
            result.append(", '").append(escape(field.name())).append(": ${item.").append(field.technicalName());
            if (field.nullable()) result.append(" ?? '-'");
            result.append("}'");
        }
        for (Field field : FlutterContract.responseFields(entity, relations)) {
            if (!field.relation()) continue;
            result.append(", '").append(escape(field.label())).append(" ID")
                    .append(field.collection() ? "s" : "").append(": ${item.").append(field.name());
            if (field.nullable()) result.append(" ?? '-'");
            result.append("}'");
        }
        return result.append("].join('\\n')").toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    public static String form(ApplicationEntity entity, List<OwnedRelation> relations, String fileName) {
        String type = entity.technicalName();
        String pk = FlutterContract.primaryKey(entity).technicalName();
        List<Field> create = FlutterContract.createFields(entity, relations);
        List<Field> update = FlutterContract.updateFields(entity, relations);
        Map<String, Field> all = new LinkedHashMap<>();
        create.forEach(field -> all.put(field.name(), field));
        update.forEach(field -> all.put(field.name(), field));
        Map<String, OwnedRelation> relationByField = new LinkedHashMap<>();
        relations.forEach(relation -> relationByField.put(relation.dtoPropertyName(), relation));

        Set<String> relatedImports = new java.util.LinkedHashSet<>();
        StringBuilder declarations = new StringBuilder();
        StringBuilder initialization = new StringBuilder();
        StringBuilder disposal = new StringBuilder();
        StringBuilder loaders = new StringBuilder();
        StringBuilder widgets = new StringBuilder();
        for (Field field : all.values()) {
            OwnedRelation relation = relationByField.get(field.name());
            if (relation != null && relation.kind() == OwnedRelation.Kind.MANY_TO_MANY) {
                appendMultiRelationSelector(entity, field, relation, relatedImports, declarations, initialization, loaders, widgets);
            } else if (relation != null) {
                appendRelationSelector(entity, field, relation, relatedImports, declarations, initialization, loaders, widgets);
            } else if (field.type() == CanonicalType.BOOLEAN && !field.collection()) {
                declarations.append("  bool").append(field.nullable() ? "?" : "").append(" _")
                        .append(field.name()).append(field.nullable() ? ";\n" : " = false;\n");
                initialization.append("    _").append(field.name()).append(" = widget.initial?.").append(field.name());
                if (!field.nullable()) initialization.append(" ?? false");
                initialization.append(";\n");
                widgets.append(booleanWidget(field));
            } else if ((field.type() == CanonicalType.DATE || field.type() == CanonicalType.DATETIME) && !field.collection()) {
                declarations.append("  DateTime? _").append(field.name()).append(";\n");
                initialization.append("    _").append(field.name()).append(" = widget.initial?.").append(field.name()).append(";\n");
                widgets.append(dateWidget(field));
            } else {
                declarations.append("  late final TextEditingController _").append(field.name()).append("Controller;\n");
                initialization.append("    _").append(field.name()).append("Controller = TextEditingController(text: ")
                        .append(initialValue(field)).append(");\n");
                disposal.append("    _").append(field.name()).append("Controller.dispose();\n");
                widgets.append(textWidget(field));
            }
        }

        return """
                import 'package:flutter/material.dart';

                import '../../core/network/api_client.dart';
                import '../../models/%s.dart';
                import '../../services/%s_service.dart';
                %s

                class %sFormScreen extends StatefulWidget {
                  final %s? initial;

                  const %sFormScreen({super.key, this.initial});

                  @override
                  State<%sFormScreen> createState() => _%sFormScreenState();
                }

                class _%sFormScreenState extends State<%sFormScreen> {
                  final _formKey = GlobalKey<FormState>();
                  final _service = const %sService();
                %s
                  bool _saving = false;
                  String? _saveError;

                  @override
                  void initState() {
                    super.initState();
                %s  }

                  @override
                  void dispose() {
                %s    super.dispose();
                  }

                %s

                  Future<void> _save() async {
                    if (!_formKey.currentState!.validate()) return;
                    setState(() { _saving = true; _saveError = null; });
                    try {
                      final request = widget.initial == null
                          ? <String, dynamic>{
                %s          }
                          : <String, dynamic>{
                %s          };
                      if (widget.initial == null) {
                        await _service.create(request);
                      } else {
                        await _service.update(widget.initial!.%s, request);
                      }
                      if (mounted) Navigator.of(context).pop(true);
                    } on ApiException catch (error) {
                      if (mounted) setState(() => _saveError = error.statusCode == 409
                          ? 'No se pudo guardar porque el registro entra en conflicto con datos existentes. Revisa si la combinacion de relaciones ya existe.'
                          : 'Error al guardar: ${error.message}');
                    } on FormatException {
                      if (mounted) setState(() => _saveError = 'Hay un valor numerico invalido.');
                    } finally {
                      if (mounted) setState(() => _saving = false);
                    }
                  }

                  @override
                  Widget build(BuildContext context) => Scaffold(
                    appBar: AppBar(title: Text(widget.initial == null ? 'Crear %s' : 'Editar %s')),
                    body: LayoutBuilder(
                      builder: (context, constraints) => Center(
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 720),
                          child: Form(
                            key: _formKey,
                            child: ListView(
                              padding: EdgeInsets.symmetric(
                                horizontal: constraints.maxWidth < 600 ? 16 : 24,
                                vertical: 16,
                              ),
                              children: [
                %s          if (_saveError != null) ...[
                            Text(_saveError!, key: const Key('save-error'), style: const TextStyle(color: Colors.red)),
                            const SizedBox(height: 12),
                          ],
                          const SizedBox(height: 16),
                          FilledButton(
                            onPressed: _saving ? null : _save,
                            child: Text(_saving ? 'Guardando...' : 'Guardar'),
                          ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  );
                }
                """.formatted(fileName, fileName, String.join("\n", relatedImports), type, type, type, type, type, type, type, type,
                declarations, initialization, disposal, loaders, requestEntries(create), requestEntries(update), pk,
                entity.name(), entity.name(), widgets);
    }

    private static String initialValue(Field field) {
        if (field.collection()) return "widget.initial?." + field.name() + "?.join(', ') ?? ''";
        if (field.type() == CanonicalType.STRING) return "widget.initial?." + field.name() + " ?? ''";
        return "widget.initial?." + field.name() + (field.nullable() ? "?.toString()" : ".toString()") + " ?? ''";
    }

    private static String textWidget(Field field) {
        String keyboard = field.type() == CanonicalType.STRING || field.type() == CanonicalType.BOOLEAN
                || field.type() == CanonicalType.DATE || field.type() == CanonicalType.DATETIME ? "TextInputType.text"
                : field.type() == CanonicalType.DECIMAL ? "const TextInputType.numberWithOptions(decimal: true)"
                : "TextInputType.number";
        String numericValidation = numericValidation(field);
        String validator = field.nullable() && numericValidation.isEmpty() ? "" :
                "            validator: (value) {\n"
                        + "              final text = value?.trim() ?? '';\n"
                        + (field.nullable() ? "" : "              if (text.isEmpty) return 'Campo requerido';\n")
                        + numericValidation
                        + "              return null;\n            },\n";
        return "          TextFormField(\n"
                + "            controller: _" + field.name() + "Controller,\n"
                + "            decoration: const InputDecoration(labelText: '" + escape(field.label()) + "'),\n"
                + "            keyboardType: " + keyboard + ",\n"
                + validator
                + "          ),\n          const SizedBox(height: 12),\n";
    }

    private static String booleanWidget(Field field) {
        if (field.nullable()) {
            return "          DropdownButtonFormField<bool>(\n"
                    + "            initialValue: _" + field.name() + ",\n"
                    + "            decoration: const InputDecoration(labelText: '" + escape(field.label()) + "'),\n"
                    + "            items: const [\n"
                    + "              DropdownMenuItem(value: null, child: Text('Sin seleccionar')),\n"
                    + "              DropdownMenuItem(value: true, child: Text('Si')),\n"
                    + "              DropdownMenuItem(value: false, child: Text('No')),\n"
                    + "            ],\n"
                    + "            onChanged: (value) => setState(() => _" + field.name() + " = value),\n"
                    + "          ),\n          const SizedBox(height: 12),\n";
        }
        return "          SwitchListTile(\n            title: const Text('" + escape(field.label()) + "'),\n"
                + "            value: _" + field.name() + ",\n"
                + "            onChanged: (value) => setState(() => _" + field.name() + " = value),\n          ),\n";
    }

    private static String dateWidget(Field field) {
        boolean dateTime = field.type() == CanonicalType.DATETIME;
        return "          FormField<DateTime>(\n"
                + "            validator: (_) => " + (field.nullable() ? "null" : "_" + field.name() + " == null ? 'Campo requerido' : null") + ",\n"
                + "            builder: (state) => ListTile(\n              contentPadding: EdgeInsets.zero,\n"
                + "              title: Text('" + escape(field.label()) + ": ${_" + field.name() + "?.toIso8601String() ?? 'sin valor'}'),\n"
                + "              subtitle: state.hasError ? Text(state.errorText!, style: const TextStyle(color: Colors.red)) : null,\n"
                + "              trailing: const Icon(Icons.calendar_today),\n              onTap: () async {\n"
                + "              final date = await showDatePicker(context: context, initialDate: _" + field.name() + " ?? DateTime.now(), firstDate: DateTime(1900), lastDate: DateTime(2200));\n"
                + "              if (date == null || !context.mounted) return;\n"
                + (dateTime
                ? "              final time = await showTimePicker(context: context, initialTime: TimeOfDay.fromDateTime(_" + field.name() + " ?? DateTime.now()));\n              if (time == null || !mounted) return;\n              setState(() => _" + field.name() + " = DateTime(date.year, date.month, date.day, time.hour, time.minute));\n"
                : "              setState(() => _" + field.name() + " = date);\n")
                + "              state.didChange(_" + field.name() + ");\n"
                + "              },\n            ),\n          ),\n";
    }

    private static void appendRelationSelector(ApplicationEntity entity, Field field, OwnedRelation relation,
                                               Set<String> imports, StringBuilder declarations,
                                               StringBuilder initialization, StringBuilder loaders,
                                               StringBuilder widgets) {
        ApplicationEntity target = relation.target();
        String targetType = target.technicalName();
        String targetFile = SpringNames.sqlName(targetType, "archivo Flutter relacionado");
        if (!target.id().equals(entity.id())) {
            imports.add("import '../../models/" + targetFile + ".dart';");
            imports.add("import '../../services/" + targetFile + "_service.dart';");
        }
        String prefix = field.name();
        String idType = FlutterTypeMapper.dartType(field.type());
        String targetPk = FlutterContract.primaryKey(target).technicalName();
        String idLabel = "'ID ${item." + targetPk + "}'";
        String visible = target.fields().stream()
                .filter(candidate -> candidate.type() == CanonicalType.STRING
                        && candidate.technicalName().equalsIgnoreCase("nombre"))
                .map(candidate -> "item." + candidate.technicalName() + (candidate.nullable() ? " ?? " + idLabel : ""))
                .findFirst().orElse(idLabel);
        declarations.append("  final _").append(prefix).append("Service = const ").append(targetType).append("Service();\n")
                .append("  List<").append(targetType).append("> _").append(prefix).append("Options = const [];\n")
                .append("  ").append(idType).append("? _").append(prefix).append(";\n")
                .append("  String? _").append(prefix).append("Error;\n")
                .append("  bool _").append(prefix).append("Loading = true;\n");
        initialization.append("    _").append(prefix).append(" = widget.initial?.").append(field.name()).append(";\n")
                .append("    _load").append(upperFirst(prefix)).append("Options();\n");
        loaders.append("  Future<void> _load").append(upperFirst(prefix)).append("Options() async {\n")
                .append("    if (mounted) setState(() { _").append(prefix).append("Loading = true; _")
                .append(prefix).append("Error = null; });\n")
                .append("    try {\n      final items = await _").append(prefix).append("Service.list();\n")
                .append("      if (mounted) setState(() { _").append(prefix).append("Options = items; _")
                .append(prefix).append("Error = null; _").append(prefix).append("Loading = false; });\n")
                .append("    } on ApiException catch (error) {\n")
                .append("      if (mounted) setState(() { _").append(prefix).append("Error = error.message; _")
                .append(prefix).append("Loading = false; });\n")
                .append("    }\n  }\n");
        widgets.append("          if (_").append(prefix).append("Loading)\n")
                .append("            const LinearProgressIndicator()\n")
                .append("          else if (_").append(prefix).append("Error != null)\n")
                .append("            InputDecorator(\n")
                .append("              decoration: InputDecoration(labelText: '").append(escape(field.label())).append("', errorText: _")
                .append(prefix).append("Error),\n")
                .append("              child: Align(alignment: Alignment.centerLeft, child: TextButton(\n")
                .append("                onPressed: _load").append(upperFirst(prefix)).append("Options, child: const Text('Reintentar'),\n")
                .append("              )),\n            )\n")
                .append("          else if (_").append(prefix).append("Options.isEmpty)\n")
                .append("            InputDecorator(\n")
                .append("              decoration: const InputDecoration(labelText: '").append(escape(field.label())).append("'),\n")
                .append("              child: const Text('No hay registros disponibles de ").append(escape(target.name()))
                .append(". Crea uno antes de continuar.'),\n            )\n")
                .append("          else\n")
                .append("          DropdownButtonFormField<").append(idType).append(">(\n")
                .append("            initialValue: _").append(prefix).append(",\n")
                .append("            decoration: const InputDecoration(labelText: '").append(escape(field.label())).append("'),\n")
                .append("            items: [\n");
        if (field.nullable()) {
            widgets.append("              const DropdownMenuItem(value: null, child: Text('Sin seleccionar')),\n");
        }
        widgets.append("              ..._").append(prefix).append("Options.map((item) => DropdownMenuItem(\n")
                .append("                value: item.").append(targetPk).append(",\n")
                .append("                child: Text(").append(visible).append("),\n              )),\n            ],\n")
                .append("            onChanged: (value) => setState(() => _").append(prefix).append(" = value),\n");
        if (!field.nullable()) {
            widgets.append("            validator: (value) => value == null ? 'Selecciona una opcion' : null,\n");
        }
        widgets.append("          ),\n          const SizedBox(height: 12),\n");
    }

    private static void appendMultiRelationSelector(ApplicationEntity entity, Field field, OwnedRelation relation,
                                                    Set<String> imports, StringBuilder declarations,
                                                    StringBuilder initialization, StringBuilder loaders,
                                                    StringBuilder widgets) {
        ApplicationEntity target = relation.target();
        String targetType = target.technicalName();
        String targetFile = SpringNames.sqlName(targetType, "archivo Flutter relacionado");
        if (!target.id().equals(entity.id())) {
            imports.add("import '../../models/" + targetFile + ".dart';");
            imports.add("import '../../services/" + targetFile + "_service.dart';");
        }
        String prefix = field.name();
        String idType = FlutterTypeMapper.dartType(field.type());
        String targetPk = FlutterContract.primaryKey(target).technicalName();
        String idLabel = "'ID ${item." + targetPk + "}'";
        String visible = target.fields().stream()
                .filter(candidate -> candidate.type() == CanonicalType.STRING
                        && candidate.technicalName().equalsIgnoreCase("nombre"))
                .map(candidate -> "item." + candidate.technicalName() + (candidate.nullable() ? " ?? " + idLabel : ""))
                .findFirst().orElse(idLabel);
        declarations.append("  final _").append(prefix).append("Service = const ").append(targetType).append("Service();\n")
                .append("  List<").append(targetType).append("> _").append(prefix).append("Options = const [];\n")
                .append("  final Set<").append(idType).append("> _").append(prefix).append(" = <").append(idType).append(">{};\n")
                .append("  String? _").append(prefix).append("Error;\n");
        initialization.append("    _").append(prefix).append(".addAll(widget.initial?.").append(field.name())
                .append(" ?? const <").append(idType).append(">[]);\n")
                .append("    _load").append(upperFirst(prefix)).append("Options();\n");
        loaders.append("  Future<void> _load").append(upperFirst(prefix)).append("Options() async {\n")
                .append("    try {\n      final items = await _").append(prefix).append("Service.list();\n")
                .append("      if (mounted) setState(() { _").append(prefix).append("Options = items; _")
                .append(prefix).append("Error = null; });\n")
                .append("    } on ApiException catch (error) {\n")
                .append("      if (mounted) setState(() => _").append(prefix).append("Error = error.message);\n")
                .append("    }\n  }\n");
        widgets.append("          FormField<Set<").append(idType).append(">>(\n")
                .append("            initialValue: _").append(prefix).append(",\n")
                .append("            validator: (value) => ").append(field.nullable() ? "null" : "value == null || value.isEmpty ? 'Selecciona al menos una opcion' : null").append(",\n")
                .append("            builder: (state) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [\n")
                .append("              Text('").append(escape(field.label())).append("', style: Theme.of(context).textTheme.titleSmall),\n")
                .append("              if (_").append(prefix).append("Error != null) Text(_").append(prefix).append("Error!, style: const TextStyle(color: Colors.red)),\n")
                .append("              ..._").append(prefix).append("Options.map((item) {\n")
                .append("                final id = item.").append(targetPk).append(";\n")
                .append("                return CheckboxListTile(\n")
                .append("                  contentPadding: EdgeInsets.zero, title: Text(").append(visible).append("),\n")
                .append("                  value: _").append(prefix).append(".contains(id),\n")
                .append("                  onChanged: (checked) => setState(() { checked == true ? _").append(prefix)
                .append(".add(id) : _").append(prefix).append(".remove(id); state.didChange(_").append(prefix).append("); }),\n")
                .append("                );\n              }),\n")
                .append("              if (state.hasError) Text(state.errorText!, style: const TextStyle(color: Colors.red)),\n")
                .append("            ]),\n          ),\n          const SizedBox(height: 12),\n");
    }

    private static String upperFirst(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String numericValidation(Field field) {
        if (field.collection()) {
            String parser = switch (field.type()) {
                case INTEGER, LONG -> "int.tryParse";
                case DECIMAL -> "double.tryParse";
                case BOOLEAN -> "bool.tryParse";
                case DATE, DATETIME -> "DateTime.tryParse";
                case STRING -> null;
            };
            if (parser == null) return "";
            return "              if (text.isNotEmpty && text.split(',').any((item) => " + parser
                    + "(item.trim()) == null)) return 'Usa IDs validos separados por comas';\n";
        }
        return switch (field.type()) {
            case INTEGER, LONG -> "              if (text.isNotEmpty && int.tryParse(text) == null) return 'Numero entero invalido';\n";
            case DECIMAL -> "              if (text.isNotEmpty && double.tryParse(text) == null) return 'Numero decimal invalido';\n";
            default -> "";
        };
    }

    private static String requestEntries(List<Field> fields) {
        StringBuilder out = new StringBuilder();
        for (Field field : fields) out.append("            '").append(field.name()).append("': ")
                .append(requestValue(field)).append(",\n");
        return out.toString();
    }

    private static String requestValue(Field field) {
        if (field.relation() && field.collection()) return "_" + field.name() + ".toList()";
        if (field.relation() && !field.collection()) {
            return field.nullable() ? "_" + field.name() : "_" + field.name() + "!";
        }
        if ((field.type() == CanonicalType.BOOLEAN || field.type() == CanonicalType.DATE
                || field.type() == CanonicalType.DATETIME) && !field.collection()) {
            String value = "_" + field.name();
            if (field.type() == CanonicalType.BOOLEAN) return value;
            return field.nullable() ? value + "?.toIso8601String()" : value + "!.toIso8601String()";
        }
        String text = "_" + field.name() + "Controller.text.trim()";
        String parsed;
        if (field.collection()) {
            String parse = switch (field.type()) {
                case STRING -> "value.trim()";
                case INTEGER, LONG -> "int.parse(value.trim())";
                case DECIMAL -> "double.parse(value.trim())";
                case BOOLEAN -> "bool.parse(value.trim())";
                case DATE, DATETIME -> "DateTime.parse(value.trim()).toIso8601String()";
            };
            parsed = text + ".isEmpty ? <" + com.sw1.backend.generator.flutter.FlutterTypeMapper.dartType(field.type())
                    + ">[] : " + text + ".split(',').map((value) => " + parse + ").toList()";
        } else {
            parsed = switch (field.type()) {
                case STRING -> text;
                case INTEGER, LONG -> "int.parse(" + text + ")";
                case DECIMAL -> "double.parse(" + text + ")";
                default -> throw new IllegalStateException("Tipo de formulario inesperado");
            };
        }
        return field.nullable() && !field.collection() ? text + ".isEmpty ? null : " + parsed : parsed;
    }
}
