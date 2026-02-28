import chromadb

chroma_client = chromadb.PersistentClient(path="./user_memory")
collection = chroma_client.get_or_create_collection("favorite_places")

def save_lieu(lieu_id: str, description: str, metadata: dict):
    """Sauvegarde un lieu favori"""
    collection.upsert(
        ids=[lieu_id],
        documents=[description],
        metadatas=[metadata]
    )
