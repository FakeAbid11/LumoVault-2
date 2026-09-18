package com.lumovault.lumovault.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One detected face. Embeddings from different [embeddingModel] values are NOT
 * comparable — clustering must refuse cross-model matches.
 */
@Entity(
    tableName = "faces",
    indices = [
        Index(value = ["media_item_id"], name = "idx_faces_media_item_id"),
        Index(value = ["person_id"], name = "idx_faces_person_id"),
    ],
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_item_id") val mediaItemId: String,
    @ColumnInfo(name = "bounding_box_x") val boundingBoxX: Double,
    @ColumnInfo(name = "bounding_box_y") val boundingBoxY: Double,
    @ColumnInfo(name = "bounding_box_width") val boundingBoxWidth: Double,
    @ColumnInfo(name = "bounding_box_height") val boundingBoxHeight: Double,
    /** Named 5-point landmarks, e.g. "left_eye" -> (x, y). */
    val landmarks: Map<String, Pair<Double, Double>> = emptyMap(),
    val embedding: List<Double> = emptyList(),

    /** Basename of the ONNX embedder that produced [embedding]. */
    @ColumnInfo(name = "embedding_model") val embeddingModel: String = "",

    /** User removed this face from its person; never re-absorbed or re-clustered. */
    val excluded: Boolean = false,
    val confidence: Double,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    @ColumnInfo(name = "person_id") val personId: Long? = null,
    /** Epoch millis. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A clustered person. [centroidModel] must equal member [FaceEntity.embeddingModel]
 * for absorption/merge comparisons to run at all.
 */
@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String? = null,
    @ColumnInfo(name = "thumbnail_face_id") val thumbnailFaceId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "centroid_embedding") val centroidEmbedding: List<Double> = emptyList(),
    @ColumnInfo(name = "centroid_model") val centroidModel: String = "",
)

/**
 * Face-to-person assignment (a face may be reassigned over its lifetime).
 */
@Entity(
    tableName = "face_persons",
    foreignKeys = [
        ForeignKey(entity = FaceEntity::class, parentColumns = ["id"], childColumns = ["face_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["person_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["face_id", "person_id"], unique = true)],
)
data class FacePersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "face_id") val faceId: Long,
    @ColumnInfo(name = "person_id") val personId: Long,
    @ColumnInfo(name = "assigned_at") val assignedAt: Long,
)

/**
 * One row per photo that has been through face detection, including photos
 * where no face was found. Without this, "already scanned" had to be inferred
 * from the faces table, so every face-less photo was re-detected each pass.
 */
@Entity(tableName = "face_scans")
data class FaceScanEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_item_id") val mediaItemId: String,
    @ColumnInfo(name = "scanned_at") val scannedAt: Long,
    @ColumnInfo(name = "face_count") val faceCount: Int = 0,
)
