package com.sw1.backend.generator;

import com.sw1.backend.generator.naming.TechnicalNameNormalizer;
import com.sw1.backend.generator.validation.ApplicationSchemaException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TechnicalNameNormalizerTest {
    @Test
    void normalizesSpacesAccentsEnyeCaseInvalidCharactersAndLeadingNumbers() {
        assertEquals("DetalleVenta", TechnicalNameNormalizer.typeName("Detalle Venta"));
        assertEquals("Categoria", TechnicalNameNormalizer.typeName("Categoría"));
        assertEquals("Nino", TechnicalNameNormalizer.typeName("niño"));
        assertEquals("DetalleVenta", TechnicalNameNormalizer.typeName("detalleVenta"));
        assertEquals("PrecioTotal", TechnicalNameNormalizer.typeName("precio---total"));
        assertEquals("N2Factor", TechnicalNameNormalizer.typeName("2 factor"));
        assertEquals("detalleVenta", TechnicalNameNormalizer.fieldName("Detalle Venta"));
        assertEquals("n2Factor", TechnicalNameNormalizer.fieldName("2 factor"));
    }

    @Test
    void rejectsEmptyOrNonIdentifierNames() {
        assertThrows(ApplicationSchemaException.class, () -> TechnicalNameNormalizer.typeName("   "));
        assertThrows(ApplicationSchemaException.class, () -> TechnicalNameNormalizer.fieldName("---"));
    }
}
