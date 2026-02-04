/**
 * MongoDB Migration Script: Remove duplicate tick candles and create unique index
 *
 * Run this script once before deploying the new code to:
 * 1. Remove duplicate candles (keeping the latest one per scripCode+windowStart)
 * 2. Create a unique compound index on (scripCode, windowStart)
 *
 * Usage:
 *   mongosh tradeIngestion scripts/mongodb_dedup_migration.js
 *
 * OR from mongo shell:
 *   use tradeIngestion
 *   load("/home/ubuntu/streamingcandle/scripts/mongodb_dedup_migration.js")
 */

print("=== MongoDB Deduplication Migration ===");
print("Database: tradeIngestion, Collection: tick_candles_1m");
print("");

// Step 1: Count duplicates before cleanup
print("Step 1: Counting duplicates...");
var duplicateStats = db.tick_candles_1m.aggregate([
    {
        $group: {
            _id: { scripCode: "$scripCode", windowStart: "$windowStart" },
            count: { $sum: 1 },
            ids: { $push: "$_id" }
        }
    },
    {
        $match: { count: { $gt: 1 } }
    },
    {
        $group: {
            _id: null,
            totalDuplicateGroups: { $sum: 1 },
            totalDuplicateDocs: { $sum: "$count" }
        }
    }
]).toArray();

if (duplicateStats.length === 0) {
    print("No duplicates found! Skipping cleanup.");
} else {
    var stats = duplicateStats[0];
    print("Found " + stats.totalDuplicateGroups + " groups with duplicates");
    print("Total documents involved: " + stats.totalDuplicateDocs);
    print("Documents to remove: " + (stats.totalDuplicateDocs - stats.totalDuplicateGroups));
    print("");

    // Step 2: Remove duplicates (keep latest by _id, which is chronological in MongoDB)
    print("Step 2: Removing duplicates (keeping latest per scripCode+windowStart)...");
    var removedCount = 0;

    db.tick_candles_1m.aggregate([
        {
            $group: {
                _id: { scripCode: "$scripCode", windowStart: "$windowStart" },
                count: { $sum: 1 },
                ids: { $push: "$_id" },
                latestId: { $last: "$_id" }
            }
        },
        {
            $match: { count: { $gt: 1 } }
        }
    ]).forEach(function(doc) {
        // Remove all except latest
        doc.ids.forEach(function(id) {
            if (id.toString() !== doc.latestId.toString()) {
                db.tick_candles_1m.deleteOne({ _id: id });
                removedCount++;
            }
        });
    });

    print("Removed " + removedCount + " duplicate documents");
}

print("");

// Step 3: Check if unique index already exists
print("Step 3: Checking existing indexes...");
var indexes = db.tick_candles_1m.getIndexes();
var hasUniqueIndex = false;

indexes.forEach(function(idx) {
    if (idx.name === "scripCode_windowStart_unique_idx") {
        hasUniqueIndex = true;
        print("Unique index already exists: " + idx.name);
    }
});

if (!hasUniqueIndex) {
    // Step 4: Create unique compound index
    print("Step 4: Creating unique compound index...");
    try {
        db.tick_candles_1m.createIndex(
            { "scripCode": 1, "windowStart": 1 },
            { unique: true, name: "scripCode_windowStart_unique_idx", background: true }
        );
        print("Successfully created unique index: scripCode_windowStart_unique_idx");
    } catch (e) {
        print("ERROR creating index: " + e.message);
        print("This may indicate remaining duplicates. Run script again.");
    }
}

print("");

// Step 5: Verify final state
print("Step 5: Verifying final state...");
var finalIndexes = db.tick_candles_1m.getIndexes();
print("Current indexes on tick_candles_1m:");
finalIndexes.forEach(function(idx) {
    print("  - " + idx.name + (idx.unique ? " (UNIQUE)" : ""));
});

var totalDocs = db.tick_candles_1m.countDocuments();
print("");
print("Total documents in collection: " + totalDocs);

// Final duplicate check
var remainingDuplicates = db.tick_candles_1m.aggregate([
    {
        $group: {
            _id: { scripCode: "$scripCode", windowStart: "$windowStart" },
            count: { $sum: 1 }
        }
    },
    {
        $match: { count: { $gt: 1 } }
    },
    {
        $count: "duplicateGroups"
    }
]).toArray();

if (remainingDuplicates.length === 0 || remainingDuplicates[0].duplicateGroups === 0) {
    print("No remaining duplicates. Migration successful!");
} else {
    print("WARNING: " + remainingDuplicates[0].duplicateGroups + " duplicate groups remain!");
    print("Run the script again to clean up.");
}

print("");
print("=== Migration Complete ===");
