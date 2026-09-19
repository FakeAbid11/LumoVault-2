package com.lumovault.lumovault.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.lumovault.lumovault.core.database.entity.FaceEntity
import com.lumovault.lumovault.core.database.entity.FacePersonEntity
import com.lumovault.lumovault.core.database.entity.FaceScanEntity
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.core.database.entity.PersonEntity
import kotlinx.coroutines.flow.Flow

/** A person with face/photo counts. Populated by SQL (see [allPeopleWithCounts])
 * — replaces the Flutter in-memory accumulation over a LEFT JOIN. */
data class PersonWithCount(
    @androidx.room.Embedded val person: PersonEntity,
    @ColumnInfo(name = "face_count") val faceCount: Int,
    @ColumnInfo(name = "photo_count") val photoCount: Int,
)

@Dao
interface FaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFace(face: FaceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFaces(faces: List<FaceEntity>)

    @Query("SELECT * FROM faces WHERE media_item_id = :mediaItemId ORDER BY confidence DESC")
    suspend fun facesForMediaItem(mediaItemId: String): List<FaceEntity>

    @Query(
        """
        SELECT DISTINCT people.name FROM faces
        INNER JOIN people ON people.id = faces.person_id
        WHERE faces.media_item_id = :mediaItemId AND people.name IS NOT NULL
        """,
    )
    suspend fun personNamesForMediaItem(mediaItemId: String): List<String>

    @Query("SELECT * FROM faces WHERE (:personId IS NULL OR person_id = :personId)")
    suspend fun allFaces(personId: Long? = null): List<FaceEntity>

    /**
     * Unassigned (not yet clustered) faces, oldest first.
     *
     * The ordering is load-bearing: clustering drains this backlog in
     * fixed-size slices, and SQLite guarantees no order without an explicit
     * ORDER BY — without it the slice boundary can shift between passes and
     * starve the tail. Excluded faces (user-removed) are filtered so they are
     * never re-minted or re-absorbed.
     */
    @Query(
        """
        SELECT * FROM faces
        WHERE person_id IS NULL AND excluded = 0
        ORDER BY created_at ASC, id ASC
        """,
    )
    suspend fun unassignedFaces(): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE person_id IS NULL AND excluded = 0 ORDER BY created_at ASC, id ASC LIMIT :limit")
    suspend fun unassignedFacesPage(limit: Int): List<FaceEntity>

    @Query("UPDATE faces SET person_id = :personId WHERE id = :faceId")
    suspend fun assignFaceToPerson(faceId: Long, personId: Long)

    @Query("UPDATE faces SET person_id = :personId WHERE id IN (:faceIds)")
    suspend fun assignFacesToPerson(faceIds: List<Long>, personId: Long)

    @Query("UPDATE faces SET person_id = NULL WHERE person_id = :personId")
    suspend fun unassignAllFaces(personId: Long)

    /** Unassign WITHOUT excluding — the "fix wrong groupings" path; faces stay
     * eligible for re-clustering. */
    @Query("UPDATE faces SET person_id = NULL WHERE id IN (:faceIds)")
    suspend fun unassignFaces(faceIds: List<Long>)

    /** User-removal ("this is not the person"): unassign AND exclude, so the
     * face never rejoins via absorption or seeds a new cluster. */
    @Query("UPDATE faces SET person_id = NULL, excluded = 1 WHERE id IN (:faceIds)")
    suspend fun excludeFaces(faceIds: List<Long>)

    @Query("SELECT * FROM media_items WHERE local_id IN (:mediaIds)")
    suspend fun mediaItemsForPerson(mediaIds: List<String>): List<MediaItemEntity>

    @Query("SELECT * FROM faces WHERE person_id = :personId ORDER BY created_at DESC")
    suspend fun facesForPerson(personId: Long): List<FaceEntity>

    // ------------------------------------------------------------- people

    @Query("SELECT * FROM people ORDER BY updated_at DESC")
    fun allPeopleFlow(): Flow<List<PersonEntity>>

    /** All people with face/photo counts, most-photographed first. */
    @Query(
        """
        SELECT people.*,
               COUNT(faces.id)          AS face_count,
               COUNT(DISTINCT faces.media_item_id) AS photo_count
        FROM people
        LEFT JOIN faces ON faces.person_id = people.id
        GROUP BY people.id
        ORDER BY photo_count DESC
        """,
    )
    fun allPeopleWithCounts(): Flow<List<PersonWithCount>>

    @Query("SELECT * FROM people")
    suspend fun allPeopleRows(): List<PersonEntity>

    @Query("SELECT * FROM people WHERE id = :id LIMIT 1")
    suspend fun personById(id: Long): PersonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createPerson(person: PersonEntity): Long

    @Query("UPDATE people SET name = :name, updated_at = :now WHERE id = :personId")
    suspend fun updatePersonName(personId: Long, name: String?, now: Long)

    @Query("UPDATE people SET thumbnail_face_id = :faceId, updated_at = :now WHERE id = :personId")
    suspend fun setPersonThumbnail(personId: Long, faceId: Long?, now: Long)

    /** Update the centroid, stamping the embedder model it was computed from —
     * centroids may only be compared against faces carrying the same tag. */
    @Query(
        """
        UPDATE people
        SET centroid_embedding = :centroid, centroid_model = :model, updated_at = :now
        WHERE id = :personId
        """,
    )
    suspend fun updateCentroid(personId: Long, centroid: List<Double>, model: String, now: Long)

    /** Delete a person and unassign all their faces. */
    @Query("UPDATE faces SET person_id = NULL WHERE person_id = :personId")
    suspend fun unassignPersonFaces(personId: Long)

    @Query("DELETE FROM people WHERE id = :personId")
    suspend fun deletePersonRow(personId: Long)

    /** Merge two people: move all faces from source to target, then delete source. */
    @Transaction
    suspend fun mergePersons(sourceId: Long, targetId: Long) {
        moveFacesToPerson(sourceId, targetId)
        deletePersonRow(sourceId)
    }

    @Query("UPDATE faces SET person_id = :targetId WHERE person_id = :sourceId")
    suspend fun moveFacesToPerson(sourceId: Long, targetId: Long)

    // ------------------------------------------------------------ counts

    @Query("SELECT COUNT(*) FROM faces")
    suspend fun faceCount(): Int

    @Query("SELECT COUNT(*) FROM faces WHERE person_id IS NOT NULL")
    suspend fun assignedFaceCount(): Int

    @Query("SELECT COUNT(*) FROM face_scans")
    suspend fun scannedMediaItemCount(): Int

    @Query("SELECT COUNT(*) AS total FROM media_items WHERE is_trashed = 0 AND is_hidden = 0")
    suspend fun mediaItemCount(): Int

    /** True when every non-trashed, non-hidden photo has a scan-log row —
     * photos with zero faces are recorded too, so this is not derivable from
     * the faces table alone. */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM face_scans) >=
               (SELECT COUNT(*) FROM media_items WHERE is_trashed = 0 AND is_hidden = 0)
        """,
    )
    suspend fun isScanningComplete(): Boolean

    // --------------------------------------------------------- scan log

    @Query("SELECT media_item_id FROM face_scans")
    suspend fun scannedMediaItemIds(): List<String>

    @Upsert
    suspend fun markMediaItemScanned(scan: FaceScanEntity)

    @Query("DELETE FROM face_scans")
    suspend fun clearScanLog()

    @Transaction
    suspend fun clearForRescan() {
        clearFaces()
        clearFacePersons()
        clearUnnamedPeople()
        clearScanLog()
    }

    @Query("DELETE FROM faces")
    suspend fun clearFaces()

    @Query("DELETE FROM face_persons")
    suspend fun clearFacePersons()

    @Query("DELETE FROM people WHERE name IS NULL OR name = ''")
    suspend fun clearUnnamedPeople()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFacePerson(facePerson: FacePersonEntity)

    @Transaction
    suspend fun deletePerson(personId: Long) {
        unassignPersonFaces(personId)
        deletePersonRow(personId)
    }
}
