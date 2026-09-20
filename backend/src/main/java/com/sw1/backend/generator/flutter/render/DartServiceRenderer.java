package com.sw1.backend.generator.flutter.render;

import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.spring.SpringNames;

public final class DartServiceRenderer {
    private DartServiceRenderer() {
    }

    public static String render(ApplicationEntity entity, String fileName) {
        String type = entity.technicalName();
        String route = SpringNames.restRoute(type);
        return """
                import '../core/network/api_client.dart';
                import '../models/%s.dart';

                class %sService {
                  const %sService();

                  static const String route = '%s';

                  Future<List<%s>> list() async {
                    final data = await ApiClient.getList(route);
                    return data.map(_decode).toList();
                  }

                  Future<%s> get(Object id) async =>
                      _decode(await ApiClient.getObject('$route/$id'));

                  Future<%s> create(Map<String, dynamic> request) async =>
                      _decode(await ApiClient.post(route, request));

                  Future<%s> update(Object id, Map<String, dynamic> request) async =>
                      _decode(await ApiClient.put('$route/$id', request));

                  Future<void> delete(Object id) => ApiClient.delete('$route/$id');

                  %s _decode(Map<String, dynamic> json) {
                    try {
                      return %s.fromJson(json);
                    } on Object {
                      throw const ApiException('El backend devolvio JSON invalido o inesperado.');
                    }
                  }
                }
                """.formatted(fileName, type, type, route, type, type, type, type, type, type);
    }
}
