package com.hackathon.setscan;

/** Optional real-network smoke check, separate from deterministic offline JUnit tests. No app cache is changed. */
public final class CatalogFeedNetworkSmoke {
    public static void main(String[] args) throws Exception {
        boolean local=args.length==2 && args[0].equals("--local");
        String json=local ? new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[1])),java.nio.charset.StandardCharsets.UTF_8) : CatalogStore.download(ItemCatalog.DEFAULT_FEED);
        java.util.List<ItemCatalog.Item> items=ItemCatalog.parse(json);
        System.out.println((local ? "Offline JVM harness check (NO network): " : "Actual app HTTPS downloader + validator: ")+items.size()+" catalog records from "+(local ? args[1] : ItemCatalog.DEFAULT_FEED));
        for(ItemCatalog.Item item:items)System.out.println(item.id+" | "+item.dimensions()+" | retrieved "+item.retrievedDate+" | "+item.sourceUrl);
        System.out.println("JVM transport/schema smoke only; not an Android UI/device or manufacturer re-scrape test.");
    }
}
