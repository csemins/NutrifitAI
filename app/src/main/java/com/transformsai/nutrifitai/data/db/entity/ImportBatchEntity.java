package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** History of XML imports (added in schema v2) so the user can see what was merged and when. */
@Entity(tableName = "import_batches", indices = {@Index("uid")})
public class ImportBatchEntity {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String uid = "";
    public String fileName;
    public int formatVersion;
    public long importedAt;
    public int added;
    public int replaced;
    public int skipped;
}
