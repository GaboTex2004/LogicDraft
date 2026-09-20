from fastapi import FastAPI

from app.api.routes import agent, ai, health, diagram, runtime, audio, image
from app.core.config import get_settings

app = FastAPI(title="Diagram AI Service", version="0.1.0")

app.include_router(health.router, prefix="/api")
app.include_router(ai.router, prefix="/api")
app.include_router(diagram.router, prefix="/api")
app.include_router(agent.router, prefix="/api")
app.include_router(runtime.router, prefix="/api")
app.include_router(audio.router, prefix="/api")
app.include_router(image.router, prefix="/api")

if __name__ == "__main__":
    import uvicorn

    settings = get_settings()
    uvicorn.run(
        "app.main:app",
        host=settings.ai_service_host,
        port=settings.ai_service_port,
        reload=True,
    )
