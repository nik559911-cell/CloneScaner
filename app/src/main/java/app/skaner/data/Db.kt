package app.skaner.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Фильтры страницы. Значения совпадают с check-ограничением в replica/schema.sql. */
object Filters {
    const val ORIGINAL = "original"
    const val WHITE = "white"
    const val LIGHTEN = "lighten"
    const val GRAY = "gray"
    const val BW = "bw"
    val all = listOf(WHITE, ORIGINAL, LIGHTEN, GRAY, BW)
}

@Entity(
    tableName = "documents",
    indices = [Index(value = ["updated_at"])],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "is_draft") val isDraft: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["document_id", "position"], unique = true)],
)
data class PageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    val position: Int,
    @ColumnInfo(name = "original_path") val originalPath: String,
    @ColumnInfo(name = "processed_path") val processedPath: String?,
    @ColumnInfo(name = "thumb_path") val thumbPath: String?,
    /** JSON [[x,y]×4] в координатах оригинала; null — весь кадр. */
    val quad: String?,
    val rotation: Int,
    val filter: String,
    /** Размер оригинала (уже повёрнутого по EXIF). */
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

data class DocSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pageCount: Int,
    val thumbPath: String?,
)

@Dao
interface ScanDao {
    @Query(
        """
        SELECT d.id AS id, d.title AS title, d.updated_at AS updatedAt,
               (SELECT COUNT(*) FROM pages p WHERE p.document_id = d.id) AS pageCount,
               (SELECT p.thumb_path FROM pages p WHERE p.document_id = d.id
                  ORDER BY p.position LIMIT 1) AS thumbPath
        FROM documents d
        WHERE d.is_draft = 0
        ORDER BY d.updated_at DESC
        """
    )
    fun observeSummaries(): Flow<List<DocSummary>>

    @Insert
    suspend fun insertDocument(doc: DocumentEntity)

    @Update
    suspend fun updateDocument(doc: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocument(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocument(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE is_draft = 1 AND updated_at < :before")
    suspend fun staleDrafts(before: Long): List<DocumentEntity>

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Query("SELECT * FROM pages WHERE document_id = :docId ORDER BY position")
    fun observePages(docId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE document_id = :docId ORDER BY position")
    suspend fun getPages(docId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getPage(id: String): PageEntity?

    @Query("SELECT * FROM pages WHERE id = :id")
    fun observePage(id: String): Flow<PageEntity?>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM pages WHERE document_id = :docId")
    suspend fun nextPosition(docId: String): Int

    @Insert
    suspend fun insertPage(page: PageEntity)

    @Update
    suspend fun updatePage(page: PageEntity)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePage(id: String)

    @Query("UPDATE pages SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Int)

    @Query("DELETE FROM pages WHERE document_id = :docId")
    suspend fun deletePagesOf(docId: String)

    @Query("SELECT original_path FROM pages UNION SELECT processed_path FROM pages WHERE processed_path IS NOT NULL UNION SELECT thumb_path FROM pages WHERE thumb_path IS NOT NULL")
    suspend fun allFilePaths(): List<String>
}

@Database(entities = [DocumentEntity::class, PageEntity::class], version = 1, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ScanDao
}
