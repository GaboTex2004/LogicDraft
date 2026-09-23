# Imagen base con Python 3.12
FROM python:3.12-slim

WORKDIR /app

# Instalar las dependencias
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copiar el código de FastAPI
COPY app/ ./app/

# Ejecutar con un usuario sin privilegios
RUN useradd --create-home appuser
USER appuser

EXPOSE 8000

# Iniciar FastAPI sin recarga automática
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]