package com.crud;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public class Main {
    public static void main(String[] args) throws Exception {
        int n = 10000; //uretilecek sayi miktari
        Random rand = new Random();

        // SQLite bağlantısı
        Connection conn = DriverManager.getConnection("jdbc:sqlite:data.db");
        conn.setAutoCommit(false);
        Statement stmt = conn.createStatement();
        stmt.execute("DROP TABLE IF EXISTS records"); // her seferinde yeniden tablo ve sayi olusturmak icin
        stmt.execute("""
            CREATE TABLE records (
                ID INTEGER PRIMARY KEY,
                Tamsayi1 INTEGER,
                Tamsayi2 INTEGER,
                GaussianTamsayi1 INTEGER,
                GaussianReelsayi1 REAL,
                GaussianReelsayi2 REAL,
                GaussianDate1 TEXT,
                GaussianDate2 TEXT,
                GaussianDate3 TEXT,
                Binary1 TEXT
            )
        """);

        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO records VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        LocalDate today = LocalDate.now();
        LocalDate baseDate = LocalDate.of(1990, 1, 1);

        for (int i = 1; i <= n; i++) {
            ps.setInt(1, i);
            ps.setInt(2, rand.nextInt(10_000_001));
            ps.setInt(3, rand.nextInt(20_000_001) - 10_000_000);
            ps.setInt(4, (int) Math.round(rand.nextGaussian() * Math.sqrt(1_000_000)));
            ps.setDouble(5, roundToDigits(rand.nextGaussian() * Math.sqrt(1_000_000), 5));
            ps.setDouble(6, roundToDigits(0.5 + rand.nextGaussian() * 1, 5));

            // GaussianDate1: gün
            int days1 = (int) Math.round(rand.nextGaussian() * Math.sqrt(10_000));
            LocalDate date1 = today.plusDays(days1);
            ps.setString(7, date1.format(DateTimeFormatter.ISO_LOCAL_DATE));

            // GaussianDate2: saniye
            int seconds2 = (int) Math.round(rand.nextGaussian() * Math.sqrt(1_000));
            LocalDateTime date2 = baseDate.atStartOfDay().plusSeconds(seconds2);
            ps.setString(8, date2.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            // GaussianDate3: timestamp
            int days3 = (int) Math.round(rand.nextGaussian() * Math.sqrt(10));
            LocalDateTime date3 = today.atStartOfDay().plusDays(days3);
            ps.setString(9, date3.toString());

            // Binary1: Gaussian HEX string
            int len = rand.nextInt(49) + 2;
            StringBuilder hex = new StringBuilder();
            for (int j = 0; j < len; j++) {
                int val = (int) Math.round(128 + rand.nextGaussian() * Math.sqrt(100));
                val = Math.max(0, Math.min(255, val));
                hex.append(String.format("%02X", val));
            }
            ps.setString(10, hex.toString());

            ps.addBatch();
            if (i % 10_000 == 0) ps.executeBatch();
        }
        ps.executeBatch();
        conn.commit();

        // 1. Kolonlar arası korelasyon/benzerlik hesapla (ör: Pearson)
        String[] columns = {"Tamsayi1", "Tamsayi2", "GaussianTamsayi1", "GaussianReelsayi1", "GaussianReelsayi2"};
        int colCount = columns.length;
        double[][] data = new double[n][colCount];

        // Verileri oku
        ResultSet rs = stmt.executeQuery("SELECT Tamsayi1, Tamsayi2, GaussianTamsayi1, GaussianReelsayi1, GaussianReelsayi2 FROM records ORDER BY ID");
        int row = 0;
        while (rs.next()) {
            for (int c = 0; c < colCount; c++) {
            data[row][c] = rs.getDouble(c + 1);
            }
            row++;
        }
        rs.close();

        // Pearson korelasyon matrisi hesapla
        double[][] corr = new double[colCount][colCount];
        for (int i = 0; i < colCount; i++) {
            for (int j = 0; j < colCount; j++) {
            corr[i][j] = pearson(data, i, j, n);
            }
        }

        // 2. Sonuçları benzerlik tablosuna kaydet
        stmt.execute("DROP TABLE IF EXISTS similarity");
        stmt.execute("""
            CREATE TABLE similarity (
            Col1 TEXT,
            Col2 TEXT,
            Pearson REAL
            )
        """);
        PreparedStatement psSim = conn.prepareStatement("INSERT INTO similarity VALUES (?, ?, ?)");
        for (int i = 0; i < colCount; i++) {
            for (int j = i + 1; j < colCount; j++) {
            psSim.setString(1, columns[i]);
            psSim.setString(2, columns[j]);
            psSim.setDouble(3, corr[i][j]);
            psSim.addBatch();
            }
        }
        System.out.println("Kolonlar arası korelasyon hesaplandı.");
        psSim.executeBatch();
        psSim.close();
        conn.commit();

        // 3. GraphViz için .dot dosyası oluştur
        StringBuilder dot = new StringBuilder();
        dot.append("graph G {\n");
        for (int i = 0; i < colCount; i++) {
            dot.append("  ").append(columns[i]).append(";\n");
        }
        for (int i = 0; i < colCount; i++) {
            for (int j = i + 1; j < colCount; j++) {
            // double absCorr = Math.abs(corr[i][j]);
            // esik deger eklemek istersek if ile absCorr > 0.5 gibi bir kontrol ekleyebiliriz
                dot.append("  ")
                   .append(columns[i])
                   .append(" -- ")
                   .append(columns[j])
                   .append(" [label=\"")
                   .append(String.format("%.2f", corr[i][j]))
                   .append("\"];\n");
            
            }
        }
        dot.append("}\n");
        java.nio.file.Files.writeString(java.nio.file.Path.of("correlation.dot"), dot.toString());

        stmt.close();
        ps.close();
        conn.close();
        System.out.println("Veri üretildi ve kaydedildi.");
    }

    private static double roundToDigits(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    // Pearson correlation coefficient calculation
    private static double pearson(double[][] data, int col1, int col2, int n) {
        double sumX = 0, sumY = 0, sumXX = 0, sumYY = 0, sumXY = 0;
        for (int i = 0; i < n; i++) {
            double x = data[i][col1];
            double y = data[i][col2];
            sumX += x;
            sumY += y;
            sumXX += x * x;
            sumYY += y * y;
            sumXY += x * y;
        }
        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt((n * sumXX - sumX * sumX) * (n * sumYY - sumY * sumY));
        if (denominator == 0) return 0;
        return numerator / denominator;
    }
}