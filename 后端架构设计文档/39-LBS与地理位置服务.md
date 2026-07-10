# LBS 与地理位置服务架构设计

## 一、问题背景

### 1.1 业务场景

基于位置的服务（Location-Based Service, LBS）是 O2O 平台的核心基础能力。几乎所有涉及线下场景的互联网服务都依赖 LBS 技术：

1. **搜索附近**：搜索附近的餐厅、电影院、便利店等 POI（Point of Interest）。
2. **配送范围**：计算商家的配送区域，判断用户是否在配送范围内。
3. **骑手调度**：实时追踪骑手位置，就近派单。
4. **位置围栏**：进入/离开特定区域时触发通知或服务。
5. **逆地理编码**：将经纬度坐标转换为结构化地址（省/市/区/街道/POI）。

### 1.2 核心问题："搜索附近"

"搜索附近 N 公里内的 POI" 是 LBS 系统中最核心也最高频的需求。这个看似简单的问题，在工程实现上面临几个挑战：

1. **海量数据**：一个大型 O2O 平台可能有数百万商家、数十万骑手的实时位置数据。
2. **实时性**：骑手位置每秒更新，位置查询需要毫秒级响应。
3. **精度要求**：不同场景对精度的要求不同，搜索附近餐厅可以容忍百米误差，但配送范围判断需要精确到十米。
4. **性能要求**：高并发场景下（如午餐高峰），每秒可能有数十万次 "附近搜索" 请求。

### 1.3 四种近距搜索算法对比

在工程实践中，"搜索附近"问题有四种经典的解决方案：

| 方案 | 核心思想 | 优点 | 缺点 |
|------|---------|------|------|
| SQL 直算距离 | 数据库中直接计算经纬度距离 | 实现简单 | O(N)全表扫描，无法分库分表 |
| 地理网格 | 将地图划分为固定大小的网格 | 直觉简单 | 网格划分固定，精度和存储的平衡难 |
| 动态网格（四叉树） | 根据密度动态分裂网格 | 自适应密度分布 | 实现复杂，跨节点查询困难 |
| GeoHash | 将二维坐标编码为一维字符串 | 存储高效，范围查询友好 | 边界效应 |

### 1.4 设计目标

| 目标 | 指标 |
|------|------|
| 查询延迟 | P99 < 50ms |
| 吞吐量 | 支持 30w QPS 位置查询 |
| 精度 | 支持米级精度 |
| 实时性 | 骑手位置 1 秒内可查 |
| 可扩展 | 支持多种 LBS 查询模式 |

---

## 二、整体架构设计

### 2.1 LBS 服务分层架构

```
┌──────────────────────────────────────────────────────────────┐
│                       业务应用层                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ 商家搜索  │  │ 骑手调度  │  │ 配送范围  │  │ 位置围栏  │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
├──────────────────────────────────────────────────────────────┤
│                       LBS 服务层                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ 位置服务  │  │ 近距搜索  │  │ 逆地理   │  │ AOI 查询  │    │
│  │          │  │ 服务     │  │ 编码服务  │  │ 服务     │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
├──────────────────────────────────────────────────────────────┤
│                     空间索引层                                │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ GeoHash  │  │ 四叉树   │  │ R-Tree   │  │ S2 Cell  │    │
│  │ 索引     │  │ 索引     │  │ 索引     │  │ 索引     │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
├──────────────────────────────────────────────────────────────┤
│                      数据存储层                               │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Redis    │  │ MySQL    │  │ ES       │  │ HBase    │    │
│  │ (实时位置)│  │ (POI)    │  │ (全文搜索)│  │ (轨迹)   │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 核心数据模型

```java
/**
 * 地理位置点
 */
@Data
public class GeoPoint {
    private double latitude;   // 纬度 [-90, 90]
    private double longitude;  // 经度 [-180, 180]

    public GeoPoint(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /**
     * 计算两点之间的球面距离（Haversine 公式）
     * 返回单位：米
     */
    public double distanceTo(GeoPoint other) {
        double EARTH_RADIUS = 6371000.0; // 地球半径（米）

        double lat1 = Math.toRadians(this.latitude);
        double lat2 = Math.toRadians(other.latitude);
        double deltaLat = Math.toRadians(other.latitude - this.latitude);
        double deltaLon = Math.toRadians(other.longitude - this.longitude);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS * c;
    }
}

/**
 * POI（兴趣点）实体
 */
@Data
@Entity
@Table(name = "t_poi")
public class POI {
    @Id
    private Long poiId;
    private String name;
    private String category;     // 餐饮、购物、休闲等
    private double latitude;
    private double longitude;
    private String geoHash;      // GeoHash 编码
    private String address;      // 详细地址
    private String province;
    private String city;
    private String district;
    private Double score;        // 评分
    private Integer status;      // 状态：1-正常，0-下线
    private Date createTime;
    private Date updateTime;
}
```

---

## 三、核心链路设计

### 3.1 GeoHash 算法详解

GeoHash 是一种将二维地理坐标编码为一维字符串的算法。它具有以下核心特性：

1. **递归二分**：对经纬度分别递归二分，大于中值取 1，小于中值取 0。
2. **交替编码**：奇数位放经度，偶数位放纬度，然后合并。
3. **Base32 编码**：将合并后的二进制串按 5 位一组转换为 Base32 字符。
4. **前缀匹配**：具有相同前缀的 GeoHash 码地理位置相近（但不绝对）。

#### 3.1.1 GeoHash 编码实现

```java
/**
 * GeoHash 编解码器
 */
public class GeoHashCodec {

    private static final char[] BASE32 = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'b', 'c', 'd', 'e', 'f', 'g', 'h', 'j', 'k', 'm',
            'n', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x',
            'y', 'z'
    };

    private static final Map<Character, Integer> BASE32_MAP = new HashMap<>();
    static {
        for (int i = 0; i < BASE32.length; i++) {
            BASE32_MAP.put(BASE32[i], i);
        }
    }

    /**
     * GeoHash 编码精度对照表
     * 字符数 -> 网格大小
     * 1: 5000km × 5000km
     * 2: 1250km × 625km
     * 3: 156km × 156km
     * 4: 39.1km × 19.5km
     * 5: 4.9km × 4.9km
     * 6: 1.2km × 0.61km
     * 7: 153m × 153m
     * 8: 38m × 19m
     * 9: 4.8m × 4.8m
     */
    private static final int DEFAULT_PRECISION = 6;

    /**
     * 将经纬度编码为 GeoHash 字符串
     *
     * @param latitude  纬度 [-90, 90]
     * @param longitude 经度 [-180, 180]
     * @param precision GeoHash 精度（字符数）
     * @return GeoHash 字符串
     */
    public static String encode(double latitude, double longitude, int precision) {
        // 总共需要的位数：precision * 5
        int totalBits = precision * 5;

        // 经度位数和纬度位数
        // 奇数位（从0开始）放经度，偶数位放纬度
        int lonBits = (totalBits + 1) / 2; // 经度位数（多一个，因为0位是经度）
        int latBits = totalBits / 2;       // 纬度位数

        // 1. 对经度进行二进制编码
        boolean[] lonCode = encodeDimension(longitude, -180.0, 180.0, lonBits);

        // 2. 对纬度进行二进制编码
        boolean[] latCode = encodeDimension(latitude, -90.0, 90.0, latBits);

        // 3. 交替合并（奇数位=经度，偶数位=纬度）
        boolean[] merged = new boolean[totalBits];
        int lonIdx = 0, latIdx = 0;
        for (int i = 0; i < totalBits; i++) {
            if (i % 2 == 0) {
                // 偶数位放经度
                merged[i] = lonCode[lonIdx++];
            } else {
                // 奇数位放纬度
                merged[i] = latCode[latIdx++];
            }
        }

        // 4. Base32 编码（每5位一组）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < totalBits; i += 5) {
            int idx = 0;
            for (int j = 0; j < 5 && (i + j) < totalBits; j++) {
                idx = (idx << 1) | (merged[i + j] ? 1 : 0);
            }
            sb.append(BASE32[idx]);
        }

        return sb.toString();
    }

    /**
     * 对单个维度进行二进制编码
     * 递归二分：大于中值取1，小于中值取0
     */
    private static boolean[] encodeDimension(double value, double min, double max,
                                              int bits) {
        boolean[] result = new boolean[bits];
        for (int i = 0; i < bits; i++) {
            double mid = (min + max) / 2;
            if (value >= mid) {
                result[i] = true;  // 1
                min = mid;
            } else {
                result[i] = false; // 0
                max = mid;
            }
        }
        return result;
    }

    /**
     * GeoHash 编码（默认精度）
     */
    public static String encode(double latitude, double longitude) {
        return encode(latitude, longitude, DEFAULT_PRECISION);
    }

    /**
     * 将 GeoHash 解码为经纬度范围
     */
    public static GeoHashBounds decode(String geoHash) {
        boolean[] bits = new boolean[geoHash.length() * 5];

        // 1. Base32 解码
        for (int i = 0; i < geoHash.length(); i++) {
            int val = BASE32_MAP.get(geoHash.charAt(i));
            for (int j = 0; j < 5; j++) {
                bits[i * 5 + j] = ((val >> (4 - j)) & 1) == 1;
            }
        }

        // 2. 分离经度和纬度位
        List<Boolean> lonBits = new ArrayList<>();
        List<Boolean> latBits = new ArrayList<>();
        for (int i = 0; i < bits.length; i++) {
            if (i % 2 == 0) {
                lonBits.add(bits[i]);
            } else {
                latBits.add(bits[i]);
            }
        }

        // 3. 解码各维度
        double[] lonRange = decodeDimension(lonBits, -180.0, 180.0);
        double[] latRange = decodeDimension(latBits, -90.0, 90.0);

        return new GeoHashBounds(
                (latRange[0] + latRange[1]) / 2,
                (lonRange[0] + lonRange[1]) / 2,
                latRange[0], latRange[1],
                lonRange[0], lonRange[1]
        );
    }

    /**
     * 解码单个维度
     */
    private static double[] decodeDimension(List<Boolean> bits, double min, double max) {
        for (Boolean bit : bits) {
            double mid = (min + max) / 2;
            if (bit) {
                min = mid;
            } else {
                max = mid;
            }
        }
        return new double[]{min, max};
    }

    /**
     * 获取 GeoHash 的 8 个邻居
     * 搜索附近时需要搜索当前格子 + 8个相邻格子
     */
    public static List<String> getNeighbors(String geoHash) {
        List<String> neighbors = new ArrayList<>(8);

        GeoHashBounds bounds = decode(geoHash);
        double centerLat = bounds.getCenterLatitude();
        double centerLon = bounds.getCenterLongitude();
        double latRange = bounds.getMaxLatitude() - bounds.getMinLatitude();
        double lonRange = bounds.getMaxLongitude() - bounds.getMinLongitude();

        int precision = geoHash.length();

        // 8个方向的偏移
        int[][] directions = {
                {-1, -1}, {-1, 0}, {-1, 1},
                {0, -1},           {0, 1},
                {1, -1},  {1, 0},  {1, 1}
        };

        for (int[] dir : directions) {
            double newLat = centerLat + dir[0] * latRange;
            double newLon = centerLon + dir[1] * lonRange;

            // 处理经纬度边界
            if (newLat > 90 || newLat < -90) continue;
            if (newLon > 180) newLon -= 360;
            if (newLon < -180) newLon += 360;

            neighbors.add(encode(newLat, newLon, precision));
        }

        return neighbors;
    }
}

/**
 * GeoHash 编码边界信息
 */
@Data
@AllArgsConstructor
public class GeoHashBounds {
    private double centerLatitude;
    private double centerLongitude;
    private double minLatitude;
    private double maxLatitude;
    private double minLongitude;
    private double maxLongitude;
}
```

#### 3.1.2 GeoHash 编码示例

```java
/**
 * GeoHash 编码过程演示
 * 以某坐标 (39.9289, 116.3883) 为例
 */
public class GeoHashDemo {

    public static void demonstrateEncoding() {
        double lat = 39.9289;   // 纬度
        double lon = 116.3883;  // 经度

        System.out.println("=== GeoHash 编码过程 ===");
        System.out.println("输入坐标: (" + lat + ", " + lon + ")");

        // 纬度编码（前3位演示）
        // 区间 [-90, 90]
        // 第1位: 39.9289 >= 0 (中值) -> 1, 区间变为 [0, 90]
        // 第2位: 39.9289 < 45 (中值) -> 0, 区间变为 [0, 45]
        // 第3位: 39.9289 >= 22.5 (中值) -> 1, 区间变为 [22.5, 45]
        System.out.println("纬度二进制: 1,0,1,...");

        // 经度编码（前3位演示）
        // 区间 [-180, 180]
        // 第1位: 116.3883 >= 0 (中值) -> 1, 区间变为 [0, 180]
        // 第2位: 116.3883 >= 90 (中值) -> 1, 区间变为 [90, 180]
        // 第3位: 116.3883 < 135 (中值) -> 0, 区间变为 [90, 135]
        System.out.println("经度二进制: 1,1,0,...");

        // 交替合并: 经,纬,经,纬,...
        // 1(经),1(纬),1(经),0(纬),0(经),1(纬),...

        // 不同精度的结果
        for (int precision = 1; precision <= 9; precision++) {
            String hash = GeoHashCodec.encode(lat, lon, precision);
            GeoHashBounds bounds = GeoHashCodec.decode(hash);
            double latRange = bounds.getMaxLatitude() - bounds.getMinLatitude();
            double lonRange = bounds.getMaxLongitude() - bounds.getMinLongitude();
            System.out.printf("精度=%d, GeoHash=%s, 网格大小≈%.1fkm×%.1fkm%n",
                    precision, hash,
                    latRange * 111, lonRange * 111 * Math.cos(Math.toRadians(lat)));
        }
    }
}
```

### 3.2 四种近距搜索方案实现

#### 3.2.1 方案一：SQL 直接计算距离

```java
/**
 * 方案一：SQL 直接计算球面距离
 * 优点：实现最简单
 * 缺点：O(N) 全表扫描，性能极差，无法分库分表
 */
@Service
public class SqlProximitySearch {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 搜索附近的 POI
     * 使用 Haversine 公式在 SQL 中计算距离
     */
    public List<NearbyPOI> searchNearby(double lat, double lon,
                                         double radiusKm, int limit) {
        // 球面距离公式 SQL
        String sql = "SELECT poi_id, name, latitude, longitude, category, " +
                "( 6371 * ACOS( " +
                "  COS(RADIANS(?)) * COS(RADIANS(latitude)) * " +
                "  COS(RADIANS(longitude) - RADIANS(?)) + " +
                "  SIN(RADIANS(?)) * SIN(RADIANS(latitude)) " +
                ") ) AS distance " +
                "FROM t_poi " +
                "WHERE status = 1 " +
                "HAVING distance < ? " +
                "ORDER BY distance " +
                "LIMIT ?";

        return jdbcTemplate.query(sql,
                new Object[]{lat, lon, lat, radiusKm, limit},
                (rs, rowNum) -> {
                    NearbyPOI poi = new NearbyPOI();
                    poi.setPoiId(rs.getLong("poi_id"));
                    poi.setName(rs.getString("name"));
                    poi.setLatitude(rs.getDouble("latitude"));
                    poi.setLongitude(rs.getDouble("longitude"));
                    poi.setCategory(rs.getString("category"));
                    poi.setDistance(rs.getDouble("distance") * 1000); // 转为米
                    return poi;
                });
    }

    /**
     * 优化1：先用矩形范围缩小候选集
     * 将全表扫描优化为索引范围查询
     */
    public List<NearbyPOI> searchNearbyOptimized(double lat, double lon,
                                                   double radiusKm, int limit) {
        // 计算经纬度范围（矩形过滤）
        double latOffset = radiusKm / 111.0; // 1度纬度≈111km
        double lonOffset = radiusKm / (111.0 * Math.cos(Math.toRadians(lat)));

        double minLat = lat - latOffset;
        double maxLat = lat + latOffset;
        double minLon = lon - lonOffset;
        double maxLon = lon + lonOffset;

        String sql = "SELECT poi_id, name, latitude, longitude, category, " +
                "( 6371 * ACOS( " +
                "  COS(RADIANS(?)) * COS(RADIANS(latitude)) * " +
                "  COS(RADIANS(longitude) - RADIANS(?)) + " +
                "  SIN(RADIANS(?)) * SIN(RADIANS(latitude)) " +
                ") ) AS distance " +
                "FROM t_poi " +
                "WHERE status = 1 " +
                "AND latitude BETWEEN ? AND ? " +    // 矩形过滤
                "AND longitude BETWEEN ? AND ? " +   // 矩形过滤
                "HAVING distance < ? " +
                "ORDER BY distance " +
                "LIMIT ?";

        return jdbcTemplate.query(sql,
                new Object[]{lat, lon, lat, minLat, maxLat, minLon, maxLon,
                        radiusKm, limit},
                (rs, rowNum) -> {
                    NearbyPOI poi = new NearbyPOI();
                    poi.setPoiId(rs.getLong("poi_id"));
                    poi.setName(rs.getString("name"));
                    poi.setLatitude(rs.getDouble("latitude"));
                    poi.setLongitude(rs.getDouble("longitude"));
                    poi.setCategory(rs.getString("category"));
                    poi.setDistance(rs.getDouble("distance") * 1000);
                    return poi;
                });
    }
}
```

#### 3.2.2 方案二：固定地理网格

```java
/**
 * 方案二：固定地理网格
 * 将地图划分为固定大小的网格，每个网格有唯一 gridID
 * 搜索时查询目标网格 + 周围8个网格
 */
@Service
public class FixedGridProximitySearch {

    // 网格大小：0.01度 ≈ 1.1km
    private static final double GRID_SIZE = 0.01;

    /**
     * 计算网格ID
     * gridID = latIndex_lonIndex
     */
    public String getGridId(double lat, double lon) {
        int latIndex = (int) Math.floor((lat + 90) / GRID_SIZE);
        int lonIndex = (int) Math.floor((lon + 180) / GRID_SIZE);
        return latIndex + "_" + lonIndex;
    }

    /**
     * 获取目标网格及周围8个网格的 gridID
     */
    public List<String> getSearchGridIds(double lat, double lon) {
        int latIndex = (int) Math.floor((lat + 90) / GRID_SIZE);
        int lonIndex = (int) Math.floor((lon + 180) / GRID_SIZE);

        List<String> gridIds = new ArrayList<>(9);
        for (int dLat = -1; dLat <= 1; dLat++) {
            for (int dLon = -1; dLon <= 1; dLon++) {
                gridIds.add((latIndex + dLat) + "_" + (lonIndex + dLon));
            }
        }
        return gridIds;
    }

    /**
     * 搜索附近 POI
     */
    public List<NearbyPOI> searchNearby(double lat, double lon,
                                         double radiusKm, int limit) {
        // 1. 获取需要搜索的网格
        List<String> gridIds = getSearchGridIds(lat, lon);

        // 2. 查询这些网格中的 POI
        String inClause = gridIds.stream()
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(","));

        String sql = "SELECT poi_id, name, latitude, longitude, category " +
                "FROM t_poi " +
                "WHERE status = 1 AND grid_id IN (" + inClause + ")";

        List<NearbyPOI> candidates = jdbcTemplate.query(sql, (rs, rowNum) -> {
            NearbyPOI poi = new NearbyPOI();
            poi.setPoiId(rs.getLong("poi_id"));
            poi.setName(rs.getString("name"));
            poi.setLatitude(rs.getDouble("latitude"));
            poi.setLongitude(rs.getDouble("longitude"));
            poi.setCategory(rs.getString("category"));
            return poi;
        });

        // 3. 精确距离过滤并排序
        GeoPoint center = new GeoPoint(lat, lon);
        return candidates.stream()
                .map(poi -> {
                    double distance = center.distanceTo(
                            new GeoPoint(poi.getLatitude(), poi.getLongitude()));
                    poi.setDistance(distance);
                    return poi;
                })
                .filter(poi -> poi.getDistance() <= radiusKm * 1000)
                .sorted(Comparator.comparingDouble(NearbyPOI::getDistance))
                .limit(limit)
                .collect(Collectors.toList());
    }
}
```

#### 3.2.3 方案三：动态网格（四叉树）

```java
/**
 * 方案三：动态网格（四叉树）
 * 当一个区域内的用户/POI数量超过阈值时，将该区域分裂为4个子区域
 * 叶子节点之间形成双向链表，便于遍历邻居节点
 */
public class QuadTreeIndex {

    private static final int SPLIT_THRESHOLD = 500; // 分裂阈值
    private static final int MAX_DEPTH = 20;        // 最大深度

    private QuadTreeNode root;

    public QuadTreeIndex() {
        // 全球范围
        root = new QuadTreeNode(
                -90, 90, -180, 180, 0
        );
    }

    /**
     * 四叉树节点
     */
    static class QuadTreeNode {
        double minLat, maxLat, minLon, maxLon;
        int depth;
        List<POI> pois;        // 叶子节点存储的 POI
        QuadTreeNode[] children; // 4个子节点：NW, NE, SW, SE

        // 叶子节点之间的双向链表指针（便于邻居遍历）
        QuadTreeNode prevLeaf;
        QuadTreeNode nextLeaf;

        boolean isLeaf;

        QuadTreeNode(double minLat, double maxLat,
                     double minLon, double maxLon, int depth) {
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.maxLon = maxLon;
            this.depth = depth;
            this.pois = new ArrayList<>();
            this.isLeaf = true;
        }

        boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat
                    && lon >= minLon && lon <= maxLon;
        }
    }

    /**
     * 插入 POI
     */
    public void insert(POI poi) {
        insert(root, poi);
    }

    private void insert(QuadTreeNode node, POI poi) {
        if (!node.contains(poi.getLatitude(), poi.getLongitude())) {
            return;
        }

        if (node.isLeaf) {
            node.pois.add(poi);

            // 超过阈值且未达最大深度时分裂
            if (node.pois.size() > SPLIT_THRESHOLD && node.depth < MAX_DEPTH) {
                split(node);
            }
        } else {
            // 递归插入到对应的子节点
            for (QuadTreeNode child : node.children) {
                if (child.contains(poi.getLatitude(), poi.getLongitude())) {
                    insert(child, poi);
                    break;
                }
            }
        }
    }

    /**
     * 节点分裂
     */
    private void split(QuadTreeNode node) {
        double midLat = (node.minLat + node.maxLat) / 2;
        double midLon = (node.minLon + node.maxLon) / 2;
        int childDepth = node.depth + 1;

        node.children = new QuadTreeNode[4];
        // NW (左上)
        node.children[0] = new QuadTreeNode(midLat, node.maxLat,
                node.minLon, midLon, childDepth);
        // NE (右上)
        node.children[1] = new QuadTreeNode(midLat, node.maxLat,
                midLon, node.maxLon, childDepth);
        // SW (左下)
        node.children[2] = new QuadTreeNode(node.minLat, midLat,
                node.minLon, midLon, childDepth);
        // SE (右下)
        node.children[3] = new QuadTreeNode(node.minLat, midLat,
                midLon, node.maxLon, childDepth);

        node.isLeaf = false;

        // 将现有 POI 分配到子节点
        for (POI poi : node.pois) {
            for (QuadTreeNode child : node.children) {
                if (child.contains(poi.getLatitude(), poi.getLongitude())) {
                    child.pois.add(poi);
                    break;
                }
            }
        }
        node.pois.clear();
    }

    /**
     * 搜索附近 POI
     */
    public List<NearbyPOI> searchNearby(double lat, double lon,
                                         double radiusKm, int limit) {
        // 计算搜索矩形
        double latOffset = radiusKm / 111.0;
        double lonOffset = radiusKm / (111.0 * Math.cos(Math.toRadians(lat)));

        double minLat = lat - latOffset;
        double maxLat = lat + latOffset;
        double minLon = lon - lonOffset;
        double maxLon = lon + lonOffset;

        // 在四叉树中范围搜索
        List<POI> candidates = new ArrayList<>();
        rangeSearch(root, minLat, maxLat, minLon, maxLon, candidates);

        // 精确距离过滤
        GeoPoint center = new GeoPoint(lat, lon);
        return candidates.stream()
                .map(poi -> {
                    NearbyPOI nearby = new NearbyPOI();
                    nearby.setPoiId(poi.getPoiId());
                    nearby.setName(poi.getName());
                    nearby.setLatitude(poi.getLatitude());
                    nearby.setLongitude(poi.getLongitude());
                    nearby.setCategory(poi.getCategory());
                    nearby.setDistance(center.distanceTo(
                            new GeoPoint(poi.getLatitude(), poi.getLongitude())));
                    return nearby;
                })
                .filter(poi -> poi.getDistance() <= radiusKm * 1000)
                .sorted(Comparator.comparingDouble(NearbyPOI::getDistance))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 范围搜索
     */
    private void rangeSearch(QuadTreeNode node, double minLat, double maxLat,
                              double minLon, double maxLon, List<POI> result) {
        // 节点范围与搜索范围不相交，跳过
        if (node.maxLat < minLat || node.minLat > maxLat
                || node.maxLon < minLon || node.minLon > maxLon) {
            return;
        }

        if (node.isLeaf) {
            result.addAll(node.pois);
        } else {
            for (QuadTreeNode child : node.children) {
                rangeSearch(child, minLat, maxLat, minLon, maxLon, result);
            }
        }
    }
}
```

#### 3.2.4 方案四：GeoHash 索引搜索

```java
/**
 * 方案四：GeoHash 索引搜索
 * key = GeoHash 编码, value = POI 列表
 * 搜索时查询当前格子 + 8个邻居格子
 */
@Service
public class GeoHashProximitySearch {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String GEOHASH_INDEX_PREFIX = "geo:poi:";

    /**
     * 添加 POI 到 GeoHash 索引
     */
    public void addPOI(POI poi) {
        String geoHash = GeoHashCodec.encode(poi.getLatitude(), poi.getLongitude(), 6);
        String key = GEOHASH_INDEX_PREFIX + geoHash;
        String value = JSON.toJSONString(poi);
        redisTemplate.opsForSet().add(key, value);
    }

    /**
     * 批量添加 POI
     */
    public void batchAddPOI(List<POI> pois) {
        // Pipeline 批量写入
        redisTemplate.executePipelined((RedisCallback<?>) connection -> {
            for (POI poi : pois) {
                String geoHash = GeoHashCodec.encode(
                        poi.getLatitude(), poi.getLongitude(), 6);
                String key = GEOHASH_INDEX_PREFIX + geoHash;
                connection.sAdd(key.getBytes(), JSON.toJSONString(poi).getBytes());
            }
            return null;
        });
    }

    /**
     * 搜索附近 POI
     * 查询当前 GeoHash 格子 + 8个邻居格子
     */
    public List<NearbyPOI> searchNearby(double lat, double lon,
                                         double radiusKm, int limit) {
        // 1. 根据搜索半径选择合适的 GeoHash 精度
        int precision = selectPrecision(radiusKm);

        // 2. 计算当前位置的 GeoHash
        String centerHash = GeoHashCodec.encode(lat, lon, precision);

        // 3. 获取 8 个邻居的 GeoHash
        List<String> neighborHashes = GeoHashCodec.getNeighbors(centerHash);

        // 4. 构建所有需要查询的 key
        List<String> keys = new ArrayList<>();
        keys.add(GEOHASH_INDEX_PREFIX + centerHash);
        for (String neighbor : neighborHashes) {
            keys.add(GEOHASH_INDEX_PREFIX + neighbor);
        }

        // 5. 批量查询
        List<POI> candidates = new ArrayList<>();
        for (String key : keys) {
            Set<String> members = redisTemplate.opsForSet().members(key);
            if (members != null) {
                for (String member : members) {
                    candidates.add(JSON.parseObject(member, POI.class));
                }
            }
        }

        // 6. 精确距离过滤并排序
        GeoPoint center = new GeoPoint(lat, lon);
        return candidates.stream()
                .map(poi -> {
                    NearbyPOI nearby = new NearbyPOI();
                    nearby.setPoiId(poi.getPoiId());
                    nearby.setName(poi.getName());
                    nearby.setLatitude(poi.getLatitude());
                    nearby.setLongitude(poi.getLongitude());
                    nearby.setCategory(poi.getCategory());
                    nearby.setDistance(center.distanceTo(
                            new GeoPoint(poi.getLatitude(), poi.getLongitude())));
                    return nearby;
                })
                .filter(poi -> poi.getDistance() <= radiusKm * 1000)
                .sorted(Comparator.comparingDouble(NearbyPOI::getDistance))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 根据搜索半径选择 GeoHash 精度
     * 需要保证 GeoHash 格子大小 < 搜索半径
     */
    private int selectPrecision(double radiusKm) {
        if (radiusKm > 20) return 4;    // 39km 格子
        if (radiusKm > 2.5) return 5;   // 4.9km 格子
        if (radiusKm > 0.6) return 6;   // 1.2km 格子
        if (radiusKm > 0.076) return 7;  // 153m 格子
        return 8;                        // 38m 格子
    }
}
```

### 3.3 Redis GeoHash 实战

Redis 内置了 GeoHash 支持，使用 52-bit 编码（精度约 0.6m），底层基于 ZSet（Skip List）存储，使用 Z-order 曲线布局。

```java
/**
 * 基于 Redis Geo 命令的位置服务
 * 核心命令：GEOADD, GEORADIUS, GEODIST, GEOPOS, GEOHASH
 */
@Service
public class RedisGeoService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String GEO_KEY_MERCHANT = "geo:merchants";
    private static final String GEO_KEY_RIDER = "geo:riders";

    /**
     * 添加商家位置
     * Redis 命令: GEOADD geo:merchants longitude latitude merchantId
     */
    public void addMerchantLocation(long merchantId, double lat, double lon) {
        redisTemplate.opsForGeo().add(
                GEO_KEY_MERCHANT,
                new Point(lon, lat),
                String.valueOf(merchantId)
        );
    }

    /**
     * 批量添加商家位置
     */
    public void batchAddMerchantLocations(List<MerchantLocation> locations) {
        Map<String, Point> memberCoordinates = new HashMap<>();
        for (MerchantLocation loc : locations) {
            memberCoordinates.put(
                    String.valueOf(loc.getMerchantId()),
                    new Point(loc.getLongitude(), loc.getLatitude())
            );
        }

        // Redis GEOADD 支持批量操作
        redisTemplate.opsForGeo().add(GEO_KEY_MERCHANT,
                memberCoordinates.entrySet().stream()
                        .map(e -> new RedisGeoCommands.GeoLocation<>(e.getKey(), e.getValue()))
                        .collect(Collectors.toList())
                        .toArray(new RedisGeoCommands.GeoLocation[0])
        );
    }

    /**
     * 更新骑手实时位置
     * 骑手位置每秒更新一次
     */
    public void updateRiderLocation(long riderId, double lat, double lon) {
        redisTemplate.opsForGeo().add(
                GEO_KEY_RIDER,
                new Point(lon, lat),
                String.valueOf(riderId)
        );
    }

    /**
     * 搜索附近的商家
     * Redis 命令: GEORADIUS geo:merchants longitude latitude radius km
     *             WITHDIST WITHCOORD COUNT limit ASC
     */
    public List<NearbyPOI> searchNearbyMerchants(double lat, double lon,
                                                   double radiusKm, int limit) {
        // 使用 GEORADIUS 搜索
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisTemplate.opsForGeo().radius(
                        GEO_KEY_MERCHANT,
                        new Circle(new Point(lon, lat),
                                new Distance(radiusKm, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .includeCoordinates()
                                .sortAscending()
                                .limit(limit)
                );

        if (results == null) {
            return Collections.emptyList();
        }

        return results.getContent().stream()
                .map(result -> {
                    NearbyPOI poi = new NearbyPOI();
                    poi.setPoiId(Long.parseLong(result.getContent().getName()));
                    Point point = result.getContent().getPoint();
                    if (point != null) {
                        poi.setLatitude(point.getY());
                        poi.setLongitude(point.getX());
                    }
                    poi.setDistance(result.getDistance().getValue() * 1000); // 转为米
                    return poi;
                })
                .collect(Collectors.toList());
    }

    /**
     * 搜索附近的骑手
     */
    public List<NearbyRider> searchNearbyRiders(double lat, double lon,
                                                  double radiusKm, int limit) {
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisTemplate.opsForGeo().radius(
                        GEO_KEY_RIDER,
                        new Circle(new Point(lon, lat),
                                new Distance(radiusKm, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .includeCoordinates()
                                .sortAscending()
                                .limit(limit)
                );

        if (results == null) {
            return Collections.emptyList();
        }

        return results.getContent().stream()
                .map(result -> {
                    NearbyRider rider = new NearbyRider();
                    rider.setRiderId(Long.parseLong(result.getContent().getName()));
                    Point point = result.getContent().getPoint();
                    if (point != null) {
                        rider.setLatitude(point.getY());
                        rider.setLongitude(point.getX());
                    }
                    rider.setDistance(result.getDistance().getValue() * 1000);
                    return rider;
                })
                .collect(Collectors.toList());
    }

    /**
     * 计算两个位置之间的距离
     * Redis 命令: GEODIST geo:merchants member1 member2 km
     */
    public Double getDistanceBetween(String geoKey, String member1, String member2) {
        Distance distance = redisTemplate.opsForGeo()
                .distance(geoKey, member1, member2, Metrics.KILOMETERS);
        return distance != null ? distance.getValue() : null;
    }

    /**
     * 获取位置的 GeoHash 编码
     * Redis 命令: GEOHASH geo:merchants merchantId
     */
    public String getGeoHash(String geoKey, String member) {
        List<String> hashes = redisTemplate.opsForGeo().hash(geoKey, member);
        return hashes != null && !hashes.isEmpty() ? hashes.get(0) : null;
    }

    /**
     * 获取位置坐标
     * Redis 命令: GEOPOS geo:merchants merchantId
     */
    public GeoPoint getPosition(String geoKey, String member) {
        List<Point> points = redisTemplate.opsForGeo().position(geoKey, member);
        if (points != null && !points.isEmpty() && points.get(0) != null) {
            Point point = points.get(0);
            return new GeoPoint(point.getY(), point.getX());
        }
        return null;
    }
}

/**
 * 附近骑手信息
 */
@Data
public class NearbyRider {
    private Long riderId;
    private double latitude;
    private double longitude;
    private double distance;     // 距离（米）
    private Integer orderCount;  // 当前在途单数
    private String status;       // 骑手状态
}
```

### 3.4 逆地理编码

```java
/**
 * 逆地理编码服务
 * 将经纬度坐标转换为结构化地址
 * 结构：省 -> 市 -> 区 -> 街道 -> POI
 */
@Service
public class ReverseGeocodingService {

    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String GEOCODING_CACHE_PREFIX = "geocoding:reverse:";

    /**
     * 逆地理编码
     * @param lat 纬度
     * @param lon 经度
     * @return 结构化地址
     */
    public StructuredAddress reverseGeocode(double lat, double lon) {
        // 1. 检查缓存
        String geoHash = GeoHashCodec.encode(lat, lon, 8); // 38m 精度
        String cacheKey = GEOCODING_CACHE_PREFIX + geoHash;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return JSON.parseObject(cached, StructuredAddress.class);
        }

        // 2. 查找所在行政区划
        StructuredAddress address = new StructuredAddress();
        address.setLatitude(lat);
        address.setLongitude(lon);

        // 使用 R-Tree 或空间索引查找包含该点的行政区划多边形
        AdministrativeRegion region = findContainingRegion(lat, lon);
        if (region != null) {
            address.setProvince(region.getProvince());
            address.setCity(region.getCity());
            address.setDistrict(region.getDistrict());
        }

        // 3. 查找最近的道路
        Road nearestRoad = findNearestRoad(lat, lon);
        if (nearestRoad != null) {
            address.setStreet(nearestRoad.getName());
            address.setStreetNumber(estimateStreetNumber(lat, lon, nearestRoad));
        }

        // 4. 查找最近的 POI
        List<NearbyPOI> nearbyPOIs = searchNearbyPOIs(lat, lon, 0.5, 3);
        if (!nearbyPOIs.isEmpty()) {
            address.setNearestPOI(nearbyPOIs.get(0).getName());
            address.setNearestPOIDistance(nearbyPOIs.get(0).getDistance());
        }

        // 5. 组装完整地址
        address.setFormattedAddress(formatAddress(address));

        // 6. 缓存
        redisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(address),
                24, TimeUnit.HOURS);

        return address;
    }

    /**
     * 批量逆地理编码
     */
    public Map<String, StructuredAddress> batchReverseGeocode(
            List<GeoPoint> points) {
        Map<String, StructuredAddress> results = new LinkedHashMap<>();

        // 先查缓存
        List<GeoPoint> missedPoints = new ArrayList<>();
        for (GeoPoint point : points) {
            String geoHash = GeoHashCodec.encode(point.getLatitude(),
                    point.getLongitude(), 8);
            String cacheKey = GEOCODING_CACHE_PREFIX + geoHash;
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                results.put(geoHash, JSON.parseObject(cached, StructuredAddress.class));
            } else {
                missedPoints.add(point);
            }
        }

        // 未命中的逐个查询
        for (GeoPoint point : missedPoints) {
            StructuredAddress address = reverseGeocode(
                    point.getLatitude(), point.getLongitude());
            String geoHash = GeoHashCodec.encode(point.getLatitude(),
                    point.getLongitude(), 8);
            results.put(geoHash, address);
        }

        return results;
    }

    private AdministrativeRegion findContainingRegion(double lat, double lon) {
        // 使用空间索引查找包含该点的行政区划
        return addressRepository.findRegionContaining(lat, lon);
    }

    private Road findNearestRoad(double lat, double lon) {
        return addressRepository.findNearestRoad(lat, lon, 0.2);
    }

    private String estimateStreetNumber(double lat, double lon, Road road) {
        // 根据在道路上的相对位置估算门牌号
        return "";
    }

    private List<NearbyPOI> searchNearbyPOIs(double lat, double lon,
                                               double radiusKm, int limit) {
        return geoHashProximitySearch.searchNearby(lat, lon, radiusKm, limit);
    }

    private String formatAddress(StructuredAddress address) {
        StringBuilder sb = new StringBuilder();
        if (address.getProvince() != null) sb.append(address.getProvince());
        if (address.getCity() != null) sb.append(address.getCity());
        if (address.getDistrict() != null) sb.append(address.getDistrict());
        if (address.getStreet() != null) sb.append(address.getStreet());
        if (address.getStreetNumber() != null) sb.append(address.getStreetNumber());
        return sb.toString();
    }
}

/**
 * 结构化地址
 */
@Data
public class StructuredAddress {
    private double latitude;
    private double longitude;
    private String province;        // 省
    private String city;            // 市
    private String district;        // 区
    private String street;          // 街道
    private String streetNumber;    // 门牌号
    private String nearestPOI;      // 最近的 POI
    private double nearestPOIDistance; // 到最近 POI 的距离（米）
    private String formattedAddress; // 格式化地址
}
```

### 3.5 配送范围计算

```java
/**
 * 配送范围服务
 * 计算商家的配送区域（AOI - Area of Interest）
 */
@Service
public class DeliveryAreaService {

    @Autowired
    private RedisGeoService redisGeoService;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 判断用户是否在商家配送范围内
     */
    public boolean isWithinDeliveryArea(long merchantId, double userLat, double userLon) {
        // 1. 获取商家配送配置
        DeliveryConfig config = getDeliveryConfig(merchantId);
        if (config == null) {
            return false;
        }

        // 2. 根据配送类型判断
        switch (config.getAreaType()) {
            case CIRCLE:
                return isWithinCircle(merchantId, userLat, userLon,
                        config.getDeliveryRadiusKm());
            case POLYGON:
                return isWithinPolygon(userLat, userLon,
                        config.getDeliveryPolygon());
            case MULTI_CIRCLE:
                return isWithinMultiCircle(merchantId, userLat, userLon,
                        config.getDeliveryCircles());
            default:
                return false;
        }
    }

    /**
     * 圆形配送范围判断
     */
    private boolean isWithinCircle(long merchantId, double userLat, double userLon,
                                    double radiusKm) {
        GeoPoint merchantPos = redisGeoService.getPosition(
                "geo:merchants", String.valueOf(merchantId));
        if (merchantPos == null) {
            return false;
        }

        double distance = merchantPos.distanceTo(new GeoPoint(userLat, userLon));
        return distance <= radiusKm * 1000;
    }

    /**
     * 多边形配送范围判断（射线法）
     */
    private boolean isWithinPolygon(double lat, double lon,
                                     List<GeoPoint> polygon) {
        int n = polygon.size();
        boolean inside = false;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            GeoPoint pi = polygon.get(i);
            GeoPoint pj = polygon.get(j);

            if ((pi.getLongitude() > lon) != (pj.getLongitude() > lon)
                    && lat < (pj.getLatitude() - pi.getLatitude())
                    * (lon - pi.getLongitude())
                    / (pj.getLongitude() - pi.getLongitude())
                    + pi.getLatitude()) {
                inside = !inside;
            }
        }

        return inside;
    }

    /**
     * 多圆配送范围（分段配送费）
     */
    private boolean isWithinMultiCircle(long merchantId, double userLat, double userLon,
                                         List<DeliveryCircle> circles) {
        GeoPoint merchantPos = redisGeoService.getPosition(
                "geo:merchants", String.valueOf(merchantId));
        if (merchantPos == null) return false;

        double distance = merchantPos.distanceTo(new GeoPoint(userLat, userLon));

        // 取最大配送半径判断
        double maxRadius = circles.stream()
                .mapToDouble(DeliveryCircle::getRadiusKm)
                .max()
                .orElse(0);

        return distance <= maxRadius * 1000;
    }

    /**
     * 计算配送费
     * 根据距离分段计费
     */
    public BigDecimal calculateDeliveryFee(long merchantId,
                                            double userLat, double userLon) {
        DeliveryConfig config = getDeliveryConfig(merchantId);
        if (config == null) {
            return BigDecimal.ZERO;
        }

        GeoPoint merchantPos = redisGeoService.getPosition(
                "geo:merchants", String.valueOf(merchantId));
        if (merchantPos == null) {
            return BigDecimal.ZERO;
        }

        double distanceKm = merchantPos.distanceTo(
                new GeoPoint(userLat, userLon)) / 1000.0;

        // 分段计费
        List<DeliveryCircle> circles = config.getDeliveryCircles();
        if (circles == null || circles.isEmpty()) {
            return config.getBaseDeliveryFee();
        }

        // 按半径排序
        circles.sort(Comparator.comparingDouble(DeliveryCircle::getRadiusKm));

        for (DeliveryCircle circle : circles) {
            if (distanceKm <= circle.getRadiusKm()) {
                return circle.getDeliveryFee();
            }
        }

        // 超出配送范围
        return BigDecimal.valueOf(-1);
    }

    /**
     * 获取商家配送配置
     */
    private DeliveryConfig getDeliveryConfig(long merchantId) {
        String key = "delivery:config:" + merchantId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return JSON.parseObject(cached, DeliveryConfig.class);
        }
        // 从数据库加载
        return deliveryConfigRepository.findByMerchantId(merchantId);
    }
}

/**
 * 配送配置
 */
@Data
public class DeliveryConfig {
    private Long merchantId;
    private AreaType areaType;          // CIRCLE / POLYGON / MULTI_CIRCLE
    private Double deliveryRadiusKm;     // 圆形配送半径
    private List<GeoPoint> deliveryPolygon; // 多边形配送区域
    private List<DeliveryCircle> deliveryCircles; // 多圆配送区域
    private BigDecimal baseDeliveryFee;  // 基础配送费
}

/**
 * 配送圆（分段配送费）
 */
@Data
public class DeliveryCircle {
    private double radiusKm;
    private BigDecimal deliveryFee;
}

/**
 * 配送区域类型
 */
public enum AreaType {
    CIRCLE,      // 圆形
    POLYGON,     // 多边形
    MULTI_CIRCLE // 多圆（分段）
}
```

### 3.6 用户位置服务

```java
/**
 * 用户位置服务
 * 管理骑手等移动用户的实时位置
 */
@Service
public class UserPositionService {

    @Autowired
    private RedisGeoService redisGeoService;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private static final String RIDER_POSITION_HISTORY = "rider:pos:history:";

    /**
     * 上报骑手位置
     * 频率：每 3 秒上报一次
     */
    public void reportRiderPosition(long riderId, double lat, double lon,
                                     double speed, double heading) {
        // 1. 更新 Redis Geo 索引（用于近距搜索）
        redisGeoService.updateRiderLocation(riderId, lat, lon);

        // 2. 记录位置详细信息
        RiderPosition position = new RiderPosition();
        position.setRiderId(riderId);
        position.setLatitude(lat);
        position.setLongitude(lon);
        position.setSpeed(speed);
        position.setHeading(heading);
        position.setTimestamp(System.currentTimeMillis());

        String key = "rider:position:" + riderId;
        redisTemplate.opsForValue().set(key, JSON.toJSONString(position),
                30, TimeUnit.SECONDS);

        // 3. 轨迹记录（用于后续分析）
        kafkaTemplate.send("rider-position-track",
                String.valueOf(riderId), JSON.toJSONString(position));

        // 4. 位置变更事件（用于订单追踪）
        publishPositionEvent(riderId, lat, lon);
    }

    /**
     * 获取骑手当前位置
     */
    public RiderPosition getRiderPosition(long riderId) {
        String key = "rider:position:" + riderId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return JSON.parseObject(cached, RiderPosition.class);
        }
        return null;
    }

    /**
     * 批量获取骑手位置
     */
    public Map<Long, RiderPosition> batchGetRiderPositions(List<Long> riderIds) {
        List<String> keys = riderIds.stream()
                .map(id -> "rider:position:" + id)
                .collect(Collectors.toList());

        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        Map<Long, RiderPosition> result = new HashMap<>();

        for (int i = 0; i < riderIds.size(); i++) {
            String val = values.get(i);
            if (val != null) {
                result.put(riderIds.get(i),
                        JSON.parseObject(val, RiderPosition.class));
            }
        }

        return result;
    }

    /**
     * 获取骑手历史轨迹
     */
    public List<RiderPosition> getRiderTrack(long riderId,
                                               long startTime, long endTime) {
        // 从 HBase 或时序数据库查询历史轨迹
        return trackRepository.findByRiderIdAndTimeRange(riderId, startTime, endTime);
    }

    /**
     * 预估骑手到达时间（ETA）
     */
    public int estimateArrivalMinutes(long riderId, double destLat, double destLon) {
        RiderPosition current = getRiderPosition(riderId);
        if (current == null) {
            return -1;
        }

        // 计算直线距离
        GeoPoint currentPoint = new GeoPoint(current.getLatitude(), current.getLongitude());
        GeoPoint destPoint = new GeoPoint(destLat, destLon);
        double distanceKm = currentPoint.distanceTo(destPoint) / 1000.0;

        // 考虑路网系数（直线距离 * 1.4 ≈ 实际距离）
        double actualDistanceKm = distanceKm * 1.4;

        // 根据骑手当前速度估算（默认 20km/h）
        double speedKmH = current.getSpeed() > 0 ? current.getSpeed() * 3.6 : 20.0;

        return (int) Math.ceil(actualDistanceKm / speedKmH * 60);
    }

    private void publishPositionEvent(long riderId, double lat, double lon) {
        // 发布位置变更事件
    }
}

/**
 * 骑手位置信息
 */
@Data
public class RiderPosition {
    private Long riderId;
    private double latitude;
    private double longitude;
    private double speed;      // 速度（m/s）
    private double heading;    // 朝向（度）
    private long timestamp;
}
```

### 3.7 商家召回服务

```java
/**
 * 商家召回服务
 * 根据用户位置召回附近可用的商家
 */
@Service
public class MerchantRecallService {

    @Autowired
    private RedisGeoService redisGeoService;
    @Autowired
    private DeliveryAreaService deliveryAreaService;
    @Autowired
    private MerchantService merchantService;

    /**
     * 召回附近可配送的商家
     */
    public List<MerchantRecallResult> recallMerchants(double userLat, double userLon,
                                                       String category, int limit) {
        // 1. 使用 Redis GEORADIUS 搜索附近的商家（扩大范围搜索）
        double searchRadiusKm = 10.0;
        List<NearbyPOI> nearbyMerchants = redisGeoService
                .searchNearbyMerchants(userLat, userLon, searchRadiusKm, limit * 3);

        if (nearbyMerchants.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 获取商家详情
        List<Long> merchantIds = nearbyMerchants.stream()
                .map(NearbyPOI::getPoiId)
                .collect(Collectors.toList());
        Map<Long, MerchantInfo> merchantInfos = merchantService
                .batchGetMerchantInfo(merchantIds);

        // 3. 过滤：营业中 + 品类匹配 + 配送范围内
        List<MerchantRecallResult> results = new ArrayList<>();
        for (NearbyPOI nearby : nearbyMerchants) {
            MerchantInfo info = merchantInfos.get(nearby.getPoiId());
            if (info == null) continue;

            // 3.1 是否营业中
            if (!info.isOpen()) continue;

            // 3.2 品类过滤
            if (category != null && !category.equals(info.getCategory())) continue;

            // 3.3 配送范围判断
            if (!deliveryAreaService.isWithinDeliveryArea(
                    nearby.getPoiId(), userLat, userLon)) continue;

            // 3.4 计算配送费
            BigDecimal deliveryFee = deliveryAreaService
                    .calculateDeliveryFee(nearby.getPoiId(), userLat, userLon);
            if (deliveryFee.compareTo(BigDecimal.ZERO) < 0) continue; // 超出配送范围

            MerchantRecallResult result = new MerchantRecallResult();
            result.setMerchantId(nearby.getPoiId());
            result.setMerchantName(info.getName());
            result.setDistance(nearby.getDistance());
            result.setDeliveryFee(deliveryFee);
            result.setScore(info.getScore());
            result.setEstimatedDeliveryMinutes(
                    estimateDeliveryTime(nearby.getDistance(), info));
            results.add(result);
        }

        // 4. 排序（综合评分：距离 + 评分 + 销量）
        results.sort(Comparator.comparingDouble(MerchantRecallResult::getRankScore).reversed());

        return results.stream().limit(limit).collect(Collectors.toList());
    }

    /**
     * 预估配送时间
     */
    private int estimateDeliveryTime(double distanceMeters, MerchantInfo merchant) {
        int preparationMinutes = merchant.getAvgPreparationMinutes(); // 平均出餐时间
        double distanceKm = distanceMeters / 1000.0;
        int deliveryMinutes = (int) Math.ceil(distanceKm * 1.4 / 20.0 * 60); // 20km/h
        return preparationMinutes + deliveryMinutes + 5; // +5 分钟缓冲
    }
}

/**
 * 商家召回结果
 */
@Data
public class MerchantRecallResult {
    private Long merchantId;
    private String merchantName;
    private double distance;             // 距离（米）
    private BigDecimal deliveryFee;       // 配送费
    private Double score;                // 评分
    private int estimatedDeliveryMinutes; // 预估配送时间

    /**
     * 综合排序分
     */
    public double getRankScore() {
        double distanceScore = Math.max(0, 1.0 - distance / 10000); // 10km 内归一化
        double ratingScore = (score != null ? score : 3.0) / 5.0;
        return distanceScore * 0.4 + ratingScore * 0.6;
    }
}
```

---

## 四、异常处理

### 4.1 位置数据异常处理

```java
/**
 * 位置数据异常检测服务
 */
@Service
public class LocationAnomalyDetector {

    /**
     * 检测位置上报是否异常
     */
    public boolean isAnomalous(long riderId, double lat, double lon) {
        // 1. 坐标范围检查
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            log.warn("坐标超出范围, riderId={}, lat={}, lon={}", riderId, lat, lon);
            return true;
        }

        // 2. 速度异常检查（瞬移检测）
        RiderPosition lastPosition = userPositionService.getRiderPosition(riderId);
        if (lastPosition != null) {
            GeoPoint lastPoint = new GeoPoint(
                    lastPosition.getLatitude(), lastPosition.getLongitude());
            GeoPoint currentPoint = new GeoPoint(lat, lon);
            double distance = lastPoint.distanceTo(currentPoint);
            long timeDiff = System.currentTimeMillis() - lastPosition.getTimestamp();

            if (timeDiff > 0) {
                double speedMs = distance / (timeDiff / 1000.0);
                // 速度超过 50m/s (180km/h) 视为异常
                if (speedMs > 50) {
                    log.warn("位置跳变异常, riderId={}, speed={}m/s", riderId, speedMs);
                    return true;
                }
            }
        }

        // 3. 位置漂移检测
        // 连续多次上报在同一位置但位移为0，可能是 GPS 漂移
        return false;
    }

    /**
     * 位置数据修正
     * 使用卡尔曼滤波对 GPS 数据进行平滑
     */
    public GeoPoint smoothPosition(long riderId, double rawLat, double rawLon) {
        RiderPosition lastPosition = userPositionService.getRiderPosition(riderId);
        if (lastPosition == null) {
            return new GeoPoint(rawLat, rawLon);
        }

        // 简化的指数移动平均（EMA）平滑
        double alpha = 0.3; // 平滑系数
        double smoothedLat = alpha * rawLat + (1 - alpha) * lastPosition.getLatitude();
        double smoothedLon = alpha * rawLon + (1 - alpha) * lastPosition.getLongitude();

        return new GeoPoint(smoothedLat, smoothedLon);
    }
}
```

### 4.2 GeoHash 边界效应处理

```java
/**
 * GeoHash 边界效应处理
 * GeoHash 的一个已知问题：两个距离很近的点可能分属不同的 GeoHash 格子
 * 解决方案：搜索时始终查询 当前格子 + 8个邻居格子
 */
@Service
public class GeoHashBoundaryHandler {

    /**
     * 处理边界效应的搜索
     * 确保不遗漏边界附近的 POI
     */
    public List<NearbyPOI> searchWithBoundaryHandling(double lat, double lon,
                                                       double radiusKm, int limit) {
        // 1. 选择合适的精度
        int precision = selectPrecision(radiusKm);

        // 2. 当前格子 + 8个邻居
        String centerHash = GeoHashCodec.encode(lat, lon, precision);
        Set<String> allHashes = new HashSet<>();
        allHashes.add(centerHash);
        allHashes.addAll(GeoHashCodec.getNeighbors(centerHash));

        // 3. 如果搜索半径大于单个格子，需要扩展搜索范围
        // 递归扩展直到覆盖搜索半径
        GeoHashBounds centerBounds = GeoHashCodec.decode(centerHash);
        double gridSizeKm = (centerBounds.getMaxLatitude() - centerBounds.getMinLatitude())
                * 111.0;

        if (radiusKm > gridSizeKm * 1.5) {
            // 需要更低精度或更大范围搜索
            int lowerPrecision = precision - 1;
            if (lowerPrecision >= 1) {
                String lowerHash = GeoHashCodec.encode(lat, lon, lowerPrecision);
                allHashes.add(lowerHash);
                allHashes.addAll(GeoHashCodec.getNeighbors(lowerHash));
            }
        }

        // 4. 搜索所有格子中的 POI
        List<POI> candidates = new ArrayList<>();
        for (String hash : allHashes) {
            Set<String> members = redisTemplate.opsForSet()
                    .members("geo:poi:" + hash);
            if (members != null) {
                for (String member : members) {
                    candidates.add(JSON.parseObject(member, POI.class));
                }
            }
        }

        // 5. 去重 + 精确距离过滤
        GeoPoint center = new GeoPoint(lat, lon);
        Map<Long, NearbyPOI> uniquePOIs = new LinkedHashMap<>();
        for (POI poi : candidates) {
            if (!uniquePOIs.containsKey(poi.getPoiId())) {
                double distance = center.distanceTo(
                        new GeoPoint(poi.getLatitude(), poi.getLongitude()));
                if (distance <= radiusKm * 1000) {
                    NearbyPOI nearby = new NearbyPOI();
                    nearby.setPoiId(poi.getPoiId());
                    nearby.setName(poi.getName());
                    nearby.setLatitude(poi.getLatitude());
                    nearby.setLongitude(poi.getLongitude());
                    nearby.setDistance(distance);
                    uniquePOIs.put(poi.getPoiId(), nearby);
                }
            }
        }

        return uniquePOIs.values().stream()
                .sorted(Comparator.comparingDouble(NearbyPOI::getDistance))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private int selectPrecision(double radiusKm) {
        if (radiusKm > 20) return 4;
        if (radiusKm > 2.5) return 5;
        if (radiusKm > 0.6) return 6;
        if (radiusKm > 0.076) return 7;
        return 8;
    }
}
```

---

## 五、性能优化

### 5.1 多级缓存策略

```java
/**
 * LBS 多级缓存
 */
@Service
public class LBSCacheService {

    // L1：本地缓存 (JVM)
    private final Cache<String, List<NearbyPOI>> localCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 带缓存的附近搜索
     * 对同一 GeoHash 格子的搜索结果缓存
     */
    public List<NearbyPOI> searchNearbyWithCache(double lat, double lon,
                                                   double radiusKm, int limit) {
        // 使用低精度 GeoHash 作为缓存 key
        // 同一格子内的不同位置共享缓存
        String cacheHash = GeoHashCodec.encode(lat, lon, 6); // 1.2km 精度
        String cacheKey = String.format("lbs:nearby:%s:%s:%.1f:%d",
                cacheHash, "all", radiusKm, limit);

        // L1 本地缓存
        List<NearbyPOI> cached = localCache.getIfPresent(cacheKey);
        if (cached != null) {
            return recalculateDistance(cached, lat, lon);
        }

        // L2 Redis 缓存
        String redisCached = redisTemplate.opsForValue().get(cacheKey);
        if (redisCached != null) {
            List<NearbyPOI> pois = JSON.parseArray(redisCached, NearbyPOI.class);
            localCache.put(cacheKey, pois);
            return recalculateDistance(pois, lat, lon);
        }

        // 实际搜索
        List<NearbyPOI> result = geoHashProximitySearch
                .searchNearby(lat, lon, radiusKm, limit);

        // 写入缓存
        localCache.put(cacheKey, result);
        redisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(result),
                60, TimeUnit.SECONDS);

        return result;
    }

    /**
     * 重新计算距离（因为缓存的是格子内的结果，需要重新算用户到POI的距离）
     */
    private List<NearbyPOI> recalculateDistance(List<NearbyPOI> pois,
                                                 double userLat, double userLon) {
        GeoPoint userPoint = new GeoPoint(userLat, userLon);
        return pois.stream()
                .map(poi -> {
                    poi.setDistance(userPoint.distanceTo(
                            new GeoPoint(poi.getLatitude(), poi.getLongitude())));
                    return poi;
                })
                .sorted(Comparator.comparingDouble(NearbyPOI::getDistance))
                .collect(Collectors.toList());
    }
}
```

### 5.2 位置上报优化

```java
/**
 * 骑手位置上报优化
 * 减少无效上报，降低服务器压力
 */
@Component
public class PositionReportOptimizer {

    private static final double MIN_DISTANCE_METERS = 10.0; // 最小移动距离
    private static final long MAX_INTERVAL_MS = 30000;       // 最大上报间隔
    private static final long MIN_INTERVAL_MS = 3000;        // 最小上报间隔

    // 每个骑手的上次上报位置
    private final ConcurrentHashMap<Long, ReportRecord> lastReports
            = new ConcurrentHashMap<>();

    /**
     * 判断是否需要上报
     * 减少无效的位置上报
     */
    public boolean shouldReport(long riderId, double lat, double lon) {
        ReportRecord lastReport = lastReports.get(riderId);
        long now = System.currentTimeMillis();

        if (lastReport == null) {
            // 首次上报
            lastReports.put(riderId, new ReportRecord(lat, lon, now));
            return true;
        }

        long timeDiff = now - lastReport.timestamp;

        // 超过最大间隔，强制上报
        if (timeDiff >= MAX_INTERVAL_MS) {
            lastReports.put(riderId, new ReportRecord(lat, lon, now));
            return true;
        }

        // 未达最小间隔，跳过
        if (timeDiff < MIN_INTERVAL_MS) {
            return false;
        }

        // 计算移动距离
        double distance = new GeoPoint(lastReport.lat, lastReport.lon)
                .distanceTo(new GeoPoint(lat, lon));

        if (distance >= MIN_DISTANCE_METERS) {
            lastReports.put(riderId, new ReportRecord(lat, lon, now));
            return true;
        }

        return false;
    }

    @Data
    @AllArgsConstructor
    private static class ReportRecord {
        double lat;
        double lon;
        long timestamp;
    }
}
```

### 5.3 Redis Geo 分片策略

```java
/**
 * Redis Geo 数据分片
 * 按城市/区域分片，避免单 key 过大
 */
@Service
public class GeoShardingService {

    /**
     * 根据位置确定分片 key
     */
    public String getShardKey(String keyPrefix, double lat, double lon) {
        // 使用 GeoHash 的前2位作为分片标识
        // 前2位 GeoHash 覆盖约 1250km × 625km 的区域
        String geoHash = GeoHashCodec.encode(lat, lon, 2);
        return keyPrefix + ":" + geoHash;
    }

    /**
     * 搜索附近时需要查询的所有分片
     */
    public List<String> getSearchShardKeys(String keyPrefix, double lat, double lon,
                                            double radiusKm) {
        Set<String> shardKeys = new HashSet<>();

        // 当前分片
        String centerHash = GeoHashCodec.encode(lat, lon, 2);
        shardKeys.add(keyPrefix + ":" + centerHash);

        // 如果搜索半径较大，可能跨分片
        if (radiusKm > 50) {
            List<String> neighbors = GeoHashCodec.getNeighbors(centerHash);
            for (String neighbor : neighbors) {
                shardKeys.add(keyPrefix + ":" + neighbor);
            }
        }

        return new ArrayList<>(shardKeys);
    }

    /**
     * 跨分片搜索
     */
    public List<NearbyPOI> searchAcrossShards(String keyPrefix, double lat, double lon,
                                                double radiusKm, int limit) {
        List<String> shardKeys = getSearchShardKeys(keyPrefix, lat, lon, radiusKm);

        List<NearbyPOI> allResults = new ArrayList<>();
        for (String shardKey : shardKeys) {
            GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                    redisTemplate.opsForGeo().radius(
                            shardKey,
                            new Circle(new Point(lon, lat),
                                    new Distance(radiusKm, Metrics.KILOMETERS)),
                            RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                    .includeDistance()
                                    .sortAscending()
                                    .limit(limit)
                    );

            if (results != null) {
                results.getContent().forEach(r -> {
                    NearbyPOI poi = new NearbyPOI();
                    poi.setPoiId(Long.parseLong(r.getContent().getName()));
                    poi.setDistance(r.getDistance().getValue() * 1000);
                    allResults.add(poi);
                });
            }
        }

        // 合并排序
        return allResults.stream()
                .sorted(Comparator.comparingDouble(NearbyPOI::getDistance))
                .limit(limit)
                .collect(Collectors.toList());
    }
}
```

---

## 六、最佳实践

### 6.1 LBS 系统设计原则

1. **选对索引方案**：对于"搜索附近"这类核心查询，推荐使用 Redis Geo（底层 GeoHash + Skip List），开箱即用且性能优异。自建索引只在有特殊需求时考虑。

2. **GeoHash 精度选择**：不同场景使用不同精度。搜索附近商家用 5-6 位（4.9km-1.2km），骑手调度用 7-8 位（153m-38m），逆地理编码用 8-9 位（38m-4.8m）。

3. **必须查 9 个格子**：GeoHash 存在边界效应，搜索时必须查询当前格子 + 8 个邻居格子，否则会遗漏边界附近的结果。

4. **先粗过滤后精确计算**：先用 GeoHash/矩形范围缩小候选集，再用 Haversine 公式精确计算距离过滤。避免在海量数据上做精确距离计算。

5. **分片存储**：当单个 Redis Geo key 中的元素数量超过百万级时，需要按区域分片，避免单 key 过大导致的性能问题。

### 6.2 位置数据处理建议

| 建议 | 说明 |
|------|------|
| GPS 数据平滑 | 原始 GPS 数据存在漂移，需要卡尔曼滤波或 EMA 平滑 |
| 异常检测 | 检测瞬移（速度异常）、位置跳变等异常上报 |
| 上报频率优化 | 根据移动距离和时间间隔动态调整上报频率 |
| 坐标系统一 | 统一使用 WGS-84 坐标系，注意国内 GCJ-02（火星坐标）转换 |
| 精度取舍 | 经纬度保留 6 位小数即可达到 0.1m 精度，无需更高 |

### 6.3 四种搜索方案选型建议

```
性能从低到高排列：

SQL直算 << 固定网格 < 四叉树 ≈ GeoHash(Redis)

推荐选择：
- 数据量 < 1万：SQL 直算 + 矩形预过滤
- 数据量 1万 ~ 100万：Redis GeoHash（GEORADIUS 命令）
- 数据量 > 100万：Redis GeoHash + 区域分片
- 特殊需求（如密度自适应）：四叉树

生产环境推荐：Redis Geo + 多级缓存 + 区域分片
```

### 6.4 关键注意事项

1. **坐标系转换**：国内使用的坐标系（GCJ-02）与 GPS 原始坐标系（WGS-84）有偏移，使用时需要正确转换，否则定位会偏移几百米。

2. **Redis Geo 内存管理**：Redis Geo 底层是 ZSet，每个元素约占用 100 字节。100 万个位置约占用 100MB 内存，需要合理规划。

3. **位置隐私**：用户位置是敏感信息，需要遵守数据保护法规。建议：
   - 位置数据不长期存储，设置过期时间
   - 对外接口只返回模糊位置（如 GeoHash 前 6 位）
   - 日志中脱敏处理经纬度

4. **降级策略**：
   - Redis 不可用时降级到数据库查询（SQL + 矩形过滤）
   - 位置服务不可用时使用用户最后一次有效位置
   - 骑手位置超过 30 秒未更新时标记为"位置过期"

5. **监控指标**：
   - 位置上报延迟
   - GEORADIUS 查询 P99 延迟
   - 位置数据覆盖率（有多少比例的骑手有有效位置）
   - 异常位置上报比例

---

## 七、全链路实战案例

前面的章节已经把 LBS 的核心算法（GeoHash）、存储方案（Redis Geo）、各类服务组件拆解清楚了。但真实生产系统中，一次业务请求往往会串联多个组件，涉及位置上报、编码、检索、排序、事件驱动、持久化等完整链路。本章通过 3 个高频业务场景，把散落的组件串成端到端的可运行链路，重点补齐工程实现中最容易被忽略但又最关键的三件事：**异常处理、日志埋点、幂等控制**。

### 7.1 案例一：附近的人/商家查询全链路

#### 7.1.1 链路概述

```
┌────────────┐  1.上报位置   ┌────────────┐  2.GeoHash编码  ┌────────────┐
│  用户 App   │ ───────────> │  位置网关   │ ─────────────> │  位置服务   │
└────────────┘              └────────────┘                └─────┬──────┘
                                                                  │ 3.GEOADD
                                                                  v
┌────────────┐  6.分页返回   ┌────────────┐  4.GEORADIUS   ┌────────────┐
│  用户 App   │ <─────────── │  查询服务   │ <───────────── │   Redis     │
└────────────┘              └─────┬──────┘                └────────────┘
                                   │ 5.距离精算 + 排序 + 分页
                                   v
                            结果游标缓存(Redis ZSet)
```

链路关键点：
1. **位置上报**：用户上报经纬度，网关做坐标系归一化（GCJ-02 -> WGS-84）与合法性校验。
2. **GeoHash 编码**：写入 Redis Geo（底层 GeoHash + ZSet）。
3. **GEORADIUS 查询**：查询当前格子及邻居，粗筛候选集。
4. **距离精算 + 排序**：用 Haversine 精确计算距离，二次过滤。
5. **分页返回**：为避免深分页穿透，采用"首页游标快照 + 游标翻页"策略。

#### 7.1.2 完整代码实现

```java
/**
 * 案例一：附近商家查询全链路服务
 *
 * 覆盖：位置上报 -> GeoHash 编码 -> GEORADIUS 查询 -> 距离排序 -> 分页返回
 * 关注点：异常处理（降级）、日志埋点、上报幂等
 */
@Service
public class NearbySearchPipeline {

    private static final Logger log = LoggerFactory.getLogger(NearbySearchPipeline.class);

    @Autowired
    private RedisGeoService redisGeoService;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private SqlProximitySearch sqlProximitySearch; // 降级用

    /** 用户上报位置的幂等去重 key 前缀 */
    private static final String REPORT_IDEMPOTENT_KEY = "lbs:report:idem:";
    /** 分页游标快照 key 前缀 */
    private static final String SEARCH_CURSOR_KEY = "lbs:search:cursor:";
    /** 游标快照过期时间：一次搜索会话有效 5 分钟 */
    private static final long CURSOR_TTL_SECONDS = 300;
    /** 单次搜索最多缓存的候选数（防止深分页无限拉取） */
    private static final int MAX_CANDIDATE = 500;

    /**
     * 用户上报位置（幂等）
     *
     * 幂等设计：客户端每次上报携带单调递增的 reportSeq（或时间戳），
     * 服务端用 SETNX 保证同一 (userId, reportSeq) 只处理一次，
     * 防止弱网重试导致的重复写入与乱序覆盖。
     *
     * @param userId    用户 ID
     * @param lat       纬度
     * @param lon       经度
     * @param reportSeq 客户端上报序号（毫秒时间戳或递增序列）
     * @return 是否为本次有效上报（false 表示被幂等拦截）
     */
    public boolean reportUserLocation(long userId, double lat, double lon, long reportSeq) {
        // 1. 参数合法性校验
        if (!isValidCoordinate(lat, lon)) {
            log.warn("[nearby-report] invalid coordinate, userId={}, lat={}, lon={}",
                    userId, lat, lon);
            throw new IllegalArgumentException("非法经纬度: lat=" + lat + ", lon=" + lon);
        }

        // 2. 幂等控制：同一上报序号只处理一次
        String idemKey = REPORT_IDEMPOTENT_KEY + userId + ":" + reportSeq;
        Boolean firstReport;
        try {
            firstReport = redisTemplate.opsForValue()
                    .setIfAbsent(idemKey, "1", 60, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Redis 异常不阻塞主流程，但记录并放行（宁可重复也不丢位置）
            log.error("[nearby-report] idempotent check failed, fallback to accept, userId={}, seq={}",
                    userId, reportSeq, e);
            firstReport = Boolean.TRUE;
        }
        if (Boolean.FALSE.equals(firstReport)) {
            log.info("[nearby-report] duplicated report ignored, userId={}, seq={}", userId, reportSeq);
            return false;
        }

        // 3. 写入 Redis Geo（GEOADD），失败时记录但不抛出（位置上报允许最终一致）
        try {
            redisGeoService.addMerchantLocation(userId, lat, lon); // 复用 GEOADD 能力
            log.info("[nearby-report] location updated, userId={}, lat={}, lon={}, seq={}",
                    userId, lat, lon, reportSeq);
        } catch (Exception e) {
            log.error("[nearby-report] GEOADD failed, userId={}, seq={}", userId, reportSeq, e);
            throw new LbsException("位置写入失败", e);
        }
        return true;
    }

    /**
     * 附近搜索（首页 + 翻页）
     *
     * 分页策略：
     * - 首页（cursor == null）：执行 GEORADIUS 拉取候选集，精算距离后排序，
     *   将有序结果写入 Redis ZSet 作为"游标快照"，返回第一页 + 游标 token。
     * - 翻页（cursor != null）：直接从游标快照 ZSet 按 rank 分页，避免重复检索，
     *   保证翻页时排序稳定，杜绝深分页穿透 Redis Geo。
     *
     * @param lat      用户纬度
     * @param lon      用户经度
     * @param radiusKm 搜索半径（公里）
     * @param cursor   游标 token；首页传 null
     * @param pageSize 每页大小
     * @return 分页结果
     */
    public NearbyPageResult searchNearby(double lat, double lon, double radiusKm,
                                         String cursor, int pageSize) {
        long start = System.currentTimeMillis();
        if (!isValidCoordinate(lat, lon)) {
            throw new IllegalArgumentException("非法经纬度");
        }
        if (pageSize <= 0 || pageSize > 100) {
            pageSize = 20; // 兜底
        }

        try {
            if (cursor == null || cursor.isEmpty()) {
                return firstPage(lat, lon, radiusKm, pageSize, start);
            } else {
                return nextPage(cursor, pageSize, start);
            }
        } catch (LbsException e) {
            throw e;
        } catch (Exception e) {
            // 4. 主链路异常 -> 降级到数据库（SQL + 矩形过滤）
            log.error("[nearby-search] redis pipeline failed, degrade to DB, lat={}, lon={}, radiusKm={}",
                    lat, lon, radiusKm, e);
            return degradeToDb(lat, lon, radiusKm, pageSize, start);
        }
    }

    /** 首页：检索 + 精算 + 排序 + 建立游标快照 */
    private NearbyPageResult firstPage(double lat, double lon, double radiusKm,
                                       int pageSize, long start) {
        // 1. GEORADIUS 粗筛候选集（Redis 底层已按距离排序，但精度有限）
        List<NearbyPOI> candidates = redisGeoService
                .searchNearbyMerchants(lat, lon, radiusKm, MAX_CANDIDATE);

        if (candidates.isEmpty()) {
            log.info("[nearby-search] first page empty, lat={}, lon={}, radiusKm={}",
                    lat, lon, radiusKm);
            return NearbyPageResult.empty();
        }

        // 2. Haversine 精算距离并二次过滤（GEORADIUS 边界误差修正）
        GeoPoint userPoint = new GeoPoint(lat, lon);
        double radiusMeters = radiusKm * 1000;
        List<NearbyPOI> sorted = candidates.stream()
                .peek(poi -> poi.setDistance(userPoint.distanceTo(
                        new GeoPoint(poi.getLatitude(), poi.getLongitude()))))
                .filter(poi -> poi.getDistance() <= radiusMeters)
                .sorted(Comparator.comparingDouble(NearbyPOI::getDistance))
                .collect(Collectors.toList());

        // 3. 建立游标快照（Redis ZSet：score = 距离，member = poiId），供翻页复用
        String cursorToken = UUID.randomUUID().toString().replace("-", "");
        String cursorKey = SEARCH_CURSOR_KEY + cursorToken;
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = sorted.stream()
                    .map(poi -> new DefaultTypedTuple<>(
                            JSON.toJSONString(poi), poi.getDistance()))
                    .collect(Collectors.toSet());
            if (!tuples.isEmpty()) {
                redisTemplate.opsForZSet().add(cursorKey, tuples);
                redisTemplate.expire(cursorKey, CURSOR_TTL_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // 快照失败不影响首页返回，只是翻页会退化为重新检索
            log.warn("[nearby-search] build cursor snapshot failed, token={}", cursorToken, e);
            cursorToken = null;
        }

        List<NearbyPOI> page = sorted.subList(0, Math.min(pageSize, sorted.size()));
        boolean hasMore = sorted.size() > pageSize;
        log.info("[nearby-search] first page done, candidate={}, matched={}, cost={}ms",
                candidates.size(), sorted.size(), System.currentTimeMillis() - start);
        return new NearbyPageResult(page, hasMore ? cursorToken : null, 0, sorted.size());
    }

    /** 翻页：从游标快照 ZSet 直接按 rank 分页 */
    private NearbyPageResult nextPage(String cursor, int pageSize, long start) {
        // cursor 编码格式：token#offset
        int sep = cursor.lastIndexOf('#');
        String token = sep > 0 ? cursor.substring(0, sep) : cursor;
        int offset = sep > 0 ? parseIntSafe(cursor.substring(sep + 1)) : 0;
        String cursorKey = SEARCH_CURSOR_KEY + token;

        Long total = redisTemplate.opsForZSet().zCard(cursorKey);
        if (total == null || total == 0) {
            // 快照已过期或不存在，提示前端重新发起首页搜索
            log.info("[nearby-search] cursor expired, token={}", token);
            throw new LbsException("搜索会话已过期，请重新搜索", null);
        }

        Set<String> members = redisTemplate.opsForZSet()
                .range(cursorKey, offset, offset + pageSize - 1);
        List<NearbyPOI> page = (members == null ? Collections.<String>emptySet() : members)
                .stream()
                .map(s -> JSON.parseObject(s, NearbyPOI.class))
                .sorted(Comparator.comparingDouble(NearbyPOI::getDistance))
                .collect(Collectors.toList());

        int nextOffset = offset + pageSize;
        boolean hasMore = nextOffset < total;
        String nextCursor = hasMore ? token + "#" + nextOffset : null;
        log.info("[nearby-search] next page done, token={}, offset={}, size={}, cost={}ms",
                token, offset, page.size(), System.currentTimeMillis() - start);
        return new NearbyPageResult(page, nextCursor, offset, total.intValue());
    }

    /** 降级：Redis 不可用时走数据库 */
    private NearbyPageResult degradeToDb(double lat, double lon, double radiusKm,
                                         int pageSize, long start) {
        try {
            List<NearbyPOI> pois = sqlProximitySearch
                    .searchNearbyOptimized(lat, lon, radiusKm, pageSize);
            log.warn("[nearby-search] degrade success from DB, size={}, cost={}ms",
                    pois.size(), System.currentTimeMillis() - start);
            // 降级模式下不提供游标翻页，仅返回首页
            return new NearbyPageResult(pois, null, 0, pois.size());
        } catch (Exception e) {
            log.error("[nearby-search] degrade to DB also failed, lat={}, lon={}", lat, lon, e);
            throw new LbsException("附近搜索服务不可用", e);
        }
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
                && !(lat == 0 && lon == 0); // (0,0) 视为无效默认值
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

/**
 * 附近搜索分页结果
 */
@Data
@AllArgsConstructor
public class NearbyPageResult {
    private List<NearbyPOI> list;
    private String nextCursor;  // 下一页游标，null 表示无更多
    private int offset;         // 当前起始偏移
    private int total;          // 快照总条数

    public static NearbyPageResult empty() {
        return new NearbyPageResult(Collections.emptyList(), null, 0, 0);
    }
}

/**
 * LBS 统一业务异常
 */
public class LbsException extends RuntimeException {
    public LbsException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

#### 7.1.3 关键设计说明

| 关注点 | 实现方式 |
|--------|---------|
| 幂等 | 上报携带 `reportSeq`，`SETNX` 保证同序号只处理一次，防止弱网重试重复写 |
| 异常降级 | Redis Geo 失败时自动降级到 `SqlProximitySearch`（SQL + 矩形过滤） |
| 深分页 | 首页建立 ZSet 游标快照，翻页直接按 rank 取，不再穿透 Redis Geo |
| 边界修正 | GEORADIUS 粗筛后用 Haversine 精算距离二次过滤 |
| 日志 | 每个阶段打点，含耗时、候选数、命中数，便于定位慢查询 |

### 7.2 案例二：骑手实时位置追踪全链路

#### 7.2.1 链路概述

```
┌──────────┐ 定时GPS上报  ┌──────────┐  校验/去抖  ┌──────────┐
│ 骑手 App  │ ──────────> │ 位置网关  │ ─────────> │ 位置服务  │
└──────────┘  每3秒       └──────────┘            └────┬─────┘
                                                        │
                       ┌────────────────────────────────┼────────────────┐
                       │ 更新Redis Geo(实时查询)          │ 发Kafka(轨迹)   │
                       v                                v                 v
                  ┌──────────┐                    ┌──────────┐      ┌──────────┐
                  │  Redis   │                    │  Kafka   │─────>│  HBase   │
                  │ (实时位置)│                    │ 轨迹Topic │轨迹消费│ (轨迹存储)│
                  └────┬─────┘                    └────┬─────┘      └──────────┘
                       │ 订阅/轮询                   │
                       v                             v
                  ┌──────────┐                 消费者端(用户/商家)
                  │ 用户 App  │  实时展示骑手位置    WebSocket 推送
                  └──────────┘
```

链路关键点：
1. **定时上报**：骑手 App 每 3 秒上报 GPS，网关做去抖（`PositionReportOptimizer`）。
2. **位置更新**：写 Redis Geo（实时查询）+ 写单点 KV（当前位置快照）。
3. **实时展示**：消费者端通过 WebSocket 订阅骑手位置变更事件。
4. **轨迹存储**：位置变更异步投递 Kafka，轨迹消费者批量落 HBase。

#### 7.2.2 完整代码实现

```java
/**
 * 案例二：骑手实时位置追踪全链路服务
 *
 * 覆盖：GPS 上报 -> 去抖校验 -> 位置更新 -> 事件推送 -> 轨迹落库
 * 关注点：乱序丢弃（时间戳幂等）、异常隔离、全链路日志
 */
@Service
public class RiderTrackingPipeline {

    private static final Logger log = LoggerFactory.getLogger(RiderTrackingPipeline.class);

    @Autowired
    private RedisGeoService redisGeoService;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private PositionReportOptimizer reportOptimizer; // 去抖
    @Autowired
    private LocationAnomalyDetector anomalyDetector; // 瞬移检测
    @Autowired
    private RiderPositionPushService pushService;    // WebSocket 推送

    private static final String RIDER_POS_KEY = "rider:position:";
    /** 记录每个骑手最近一次成功处理的上报时间戳，用于乱序丢弃 */
    private static final String RIDER_LAST_TS_KEY = "rider:pos:lastts:";
    private static final String TRACK_TOPIC = "rider-position-track";

    /**
     * 处理一次骑手 GPS 上报（全链路入口）
     *
     * 幂等/乱序控制：GPS 上报可能因网络抖动乱序到达，
     * 用客户端 GPS 采集时间戳 gpsTime 做单调性校验，
     * 只接受比"上次已处理时间戳"更新的上报，丢弃过期/重复的旧点，
     * 避免骑手位置在地图上"回跳"。
     *
     * @param riderId 骑手 ID
     * @param lat     纬度
     * @param lon     经度
     * @param speed   速度（m/s）
     * @param heading 朝向（度）
     * @param gpsTime GPS 采集时间戳（客户端）
     * @return 处理结果
     */
    public ReportResult onGpsReport(long riderId, double lat, double lon,
                                    double speed, double heading, long gpsTime) {
        long start = System.currentTimeMillis();

        // 1. 合法性校验
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            log.warn("[rider-track] invalid gps, riderId={}, lat={}, lon={}", riderId, lat, lon);
            return ReportResult.rejected("非法坐标");
        }

        // 2. 乱序/重复丢弃（幂等核心）
        if (isStaleReport(riderId, gpsTime)) {
            log.info("[rider-track] stale report dropped, riderId={}, gpsTime={}", riderId, gpsTime);
            return ReportResult.rejected("过期或乱序上报");
        }

        // 3. 去抖：移动距离/时间未达阈值则跳过（降低写压力）
        if (!reportOptimizer.shouldReport(riderId, lat, lon)) {
            log.debug("[rider-track] throttled, riderId={}", riderId);
            return ReportResult.throttled();
        }

        // 4. 异常检测：瞬移（速度异常）等，异常点仅告警不阻断
        RiderPosition last = getCurrentPosition(riderId);
        if (last != null && anomalyDetector.isTeleport(last, lat, lon, gpsTime)) {
            log.warn("[rider-track] teleport detected, riderId={}, from=({},{}) to=({},{})",
                    riderId, last.getLatitude(), last.getLongitude(), lat, lon);
            // 瞬移点不更新实时位置，但仍记录轨迹以便排查
            sendTrackAsync(riderId, lat, lon, speed, heading, gpsTime, true);
            return ReportResult.anomaly();
        }

        RiderPosition position = buildPosition(riderId, lat, lon, speed, heading, gpsTime);

        // 5. 更新 Redis Geo + 当前位置快照（实时查询依赖）
        try {
            redisGeoService.updateRiderLocation(riderId, lat, lon);
            redisTemplate.opsForValue().set(RIDER_POS_KEY + riderId,
                    JSON.toJSONString(position), 30, TimeUnit.SECONDS);
            // 更新已处理时间戳水位线
            updateLastTs(riderId, gpsTime);
        } catch (Exception e) {
            log.error("[rider-track] update redis failed, riderId={}, gpsTime={}", riderId, gpsTime, e);
            // 实时位置更新失败仍尝试落轨迹，保证数据不丢
            sendTrackAsync(riderId, lat, lon, speed, heading, gpsTime, false);
            throw new LbsException("骑手位置更新失败", e);
        }

        // 6. 实时推送给消费者端（用户/商家），推送失败不影响主流程
        try {
            pushService.pushToSubscribers(riderId, position);
        } catch (Exception e) {
            log.warn("[rider-track] push failed, riderId={}", riderId, e);
        }

        // 7. 异步落轨迹（Kafka -> HBase）
        sendTrackAsync(riderId, lat, lon, speed, heading, gpsTime, false);

        log.info("[rider-track] report done, riderId={}, lat={}, lon={}, cost={}ms",
                riderId, lat, lon, System.currentTimeMillis() - start);
        return ReportResult.accepted();
    }

    /**
     * 乱序/重复判定：只接受 gpsTime 严格大于水位线的上报
     */
    private boolean isStaleReport(long riderId, long gpsTime) {
        try {
            String lastTsStr = redisTemplate.opsForValue().get(RIDER_LAST_TS_KEY + riderId);
            if (lastTsStr == null) {
                return false;
            }
            long lastTs = Long.parseLong(lastTsStr);
            return gpsTime <= lastTs;
        } catch (Exception e) {
            // 水位线读取失败时放行，宁可处理也不误丢
            log.warn("[rider-track] read last ts failed, riderId={}", riderId, e);
            return false;
        }
    }

    private void updateLastTs(long riderId, long gpsTime) {
        try {
            redisTemplate.opsForValue().set(RIDER_LAST_TS_KEY + riderId,
                    String.valueOf(gpsTime), 60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[rider-track] update last ts failed, riderId={}", riderId, e);
        }
    }

    /**
     * 异步投递轨迹到 Kafka（消费者批量落 HBase）
     * 用 riderId 作为分区 key，保证同一骑手轨迹有序
     */
    private void sendTrackAsync(long riderId, double lat, double lon, double speed,
                                double heading, long gpsTime, boolean anomaly) {
        RiderTrackPoint point = new RiderTrackPoint(riderId, lat, lon, speed,
                heading, gpsTime, anomaly);
        try {
            kafkaTemplate.send(TRACK_TOPIC, String.valueOf(riderId), JSON.toJSONString(point))
                    .addCallback(
                            ok -> log.debug("[rider-track] track sent, riderId={}", riderId),
                            ex -> log.error("[rider-track] track send failed, riderId={}", riderId, ex)
                    );
        } catch (Exception e) {
            log.error("[rider-track] kafka send exception, riderId={}", riderId, e);
        }
    }

    private RiderPosition getCurrentPosition(long riderId) {
        try {
            String cached = redisTemplate.opsForValue().get(RIDER_POS_KEY + riderId);
            return cached == null ? null : JSON.parseObject(cached, RiderPosition.class);
        } catch (Exception e) {
            log.warn("[rider-track] read current position failed, riderId={}", riderId, e);
            return null;
        }
    }

    private RiderPosition buildPosition(long riderId, double lat, double lon,
                                        double speed, double heading, long gpsTime) {
        RiderPosition p = new RiderPosition();
        p.setRiderId(riderId);
        p.setLatitude(lat);
        p.setLongitude(lon);
        p.setSpeed(speed);
        p.setHeading(heading);
        p.setTimestamp(gpsTime);
        return p;
    }
}

/**
 * 轨迹消费者：批量消费 Kafka 轨迹并落 HBase
 */
@Component
public class RiderTrackConsumer {

    private static final Logger log = LoggerFactory.getLogger(RiderTrackConsumer.class);

    @Autowired
    private HBaseTrackRepository trackRepository;

    /**
     * 批量消费轨迹点
     *
     * 幂等落库：HBase rowKey 设计为 riderId_reverseTs（gpsTime 反转），
     * 相同 (riderId, gpsTime) 的重复投递会覆盖写同一 rowKey，天然幂等。
     */
    @KafkaListener(topics = "rider-position-track", groupId = "track-persist",
            containerFactory = "batchFactory")
    public void consume(List<ConsumerRecord<String, String>> records) {
        long start = System.currentTimeMillis();
        List<RiderTrackPoint> batch = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                batch.add(JSON.parseObject(record.value(), RiderTrackPoint.class));
            } catch (Exception e) {
                // 单条解析失败不影响整批，跳过并告警
                log.error("[track-consumer] parse failed, offset={}, value={}",
                        record.offset(), record.value(), e);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            trackRepository.batchPut(batch); // rowKey 幂等，重复消费安全
            log.info("[track-consumer] persisted, size={}, cost={}ms",
                    batch.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 落库失败抛出，交由 Kafka 重试（幂等保证重复安全）
            log.error("[track-consumer] batchPut failed, size={}", batch.size(), e);
            throw new LbsException("轨迹落库失败", e);
        }
    }
}

/**
 * 骑手轨迹点
 */
@Data
@AllArgsConstructor
public class RiderTrackPoint {
    private long riderId;
    private double latitude;
    private double longitude;
    private double speed;
    private double heading;
    private long gpsTime;
    private boolean anomaly;   // 是否为异常点（如瞬移）
}

/**
 * 上报处理结果
 */
@Data
@AllArgsConstructor
public class ReportResult {
    private String status;     // ACCEPTED / REJECTED / THROTTLED / ANOMALY
    private String message;

    public static ReportResult accepted() { return new ReportResult("ACCEPTED", "ok"); }
    public static ReportResult throttled() { return new ReportResult("THROTTLED", "去抖跳过"); }
    public static ReportResult anomaly() { return new ReportResult("ANOMALY", "异常点"); }
    public static ReportResult rejected(String msg) { return new ReportResult("REJECTED", msg); }
}
```

#### 7.2.3 关键设计说明

| 关注点 | 实现方式 |
|--------|---------|
| 乱序幂等 | 用 GPS 采集时间戳做水位线，只接受更新的上报，防止地图"回跳" |
| 去抖 | 复用 `PositionReportOptimizer`，移动/时间未达阈值不写入 |
| 异常隔离 | 推送失败、轨迹发送失败均 try-catch 不阻断主链路 |
| 落库幂等 | HBase rowKey = riderId_reverseTs，重复消费覆盖同一行 |
| 有序性 | Kafka 以 riderId 为分区 key，保证同一骑手轨迹分区内有序 |

### 7.3 案例三：地理围栏全链路

#### 7.3.1 链路概述

```
┌──────────┐ 1.配置围栏  ┌──────────┐  2.预计算GeoHash格子  ┌──────────┐
│ 运营后台  │ ─────────> │ 围栏服务  │ ───────────────────> │  Redis    │
└──────────┘            └──────────┘   (围栏->格子倒排索引)  │ 围栏索引  │
                                                            └────┬─────┘
┌──────────┐ 3.位置上报  ┌──────────┐  4.格子命中候选围栏      │
│ 用户 App  │ ─────────> │ 判定引擎  │ <───────────────────────┘
└──────────┘            └────┬─────┘  5.多边形精判(射线法)
                             │
                             │ 6.对比上次状态，产生进/出事件
                             v
                       ┌──────────┐  7.事件驱动  ┌──────────┐
                       │  事件总线 │ ──────────> │ 通知/营销 │
                       └──────────┘             └──────────┘
```

链路关键点：
1. **围栏配置**：运营配置多边形/圆形围栏，服务端预计算其覆盖的 GeoHash 格子，建立"格子 -> 围栏"倒排索引，避免每次上报都遍历所有围栏。
2. **位置判定**：先用 GeoHash 格子快速召回候选围栏，再用射线法（多边形）或距离（圆形）精判。
3. **进出事件**：对比用户上次围栏状态，仅在状态跃迁时产生 ENTER/EXIT 事件。
4. **触发动作**：事件投递到总线，驱动通知/营销活动。

#### 7.3.2 完整代码实现

```java
/**
 * 案例三：地理围栏全链路服务
 *
 * 覆盖：围栏配置 -> 格子倒排 -> 位置判定 -> 进出事件 -> 触发动作
 * 关注点：事件幂等（状态跃迁去重）、精判异常兜底、全链路日志
 */
@Service
public class GeoFencePipeline {

    private static final Logger log = LoggerFactory.getLogger(GeoFencePipeline.class);

    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private GeoFenceRepository fenceRepository;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    /** 格子 -> 围栏ID 倒排索引 key 前缀（Set 结构） */
    private static final String CELL_FENCE_INDEX = "fence:cell:";
    /** 用户当前所处围栏集合 key 前缀（用于状态对比） */
    private static final String USER_FENCE_STATE = "fence:user:state:";
    /** 事件幂等 key 前缀 */
    private static final String EVENT_IDEMPOTENT = "fence:event:idem:";
    /** 围栏索引使用的 GeoHash 精度（7 位 ≈ 153m 格子） */
    private static final int FENCE_GEOHASH_PRECISION = 7;
    private static final String FENCE_EVENT_TOPIC = "geo-fence-event";

    /**
     * 配置围栏：预计算覆盖格子并建立倒排索引
     *
     * 幂等：先删除该围栏旧的格子索引再重建，保证重复配置结果一致。
     *
     * @param fence 围栏定义（多边形顶点或圆心+半径）
     */
    public void configureFence(GeoFence fence) {
        if (fence == null || fence.getFenceId() == null) {
            throw new IllegalArgumentException("围栏定义为空");
        }
        long start = System.currentTimeMillis();

        try {
            // 1. 计算围栏外接矩形，枚举覆盖的 GeoHash 格子
            Set<String> cells = computeCoveringCells(fence);
            if (cells.isEmpty()) {
                log.warn("[fence-config] no covering cells, fenceId={}", fence.getFenceId());
                return;
            }

            // 2. 幂等重建：先清理旧索引
            removeFenceFromIndex(fence.getFenceId());

            // 3. 建立"格子 -> 围栏"倒排索引
            for (String cell : cells) {
                redisTemplate.opsForSet().add(CELL_FENCE_INDEX + cell,
                        String.valueOf(fence.getFenceId()));
            }
            // 4. 持久化围栏定义（含反查用的格子列表，供后续清理）
            fence.setCoveringCells(cells);
            fenceRepository.save(fence);

            log.info("[fence-config] done, fenceId={}, cells={}, cost={}ms",
                    fence.getFenceId(), cells.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[fence-config] failed, fenceId={}", fence.getFenceId(), e);
            throw new LbsException("围栏配置失败", e);
        }
    }

    /**
     * 用户位置判定：召回候选围栏 -> 精判 -> 产生进出事件
     *
     * @param userId 用户 ID
     * @param lat    纬度
     * @param lon    经度
     * @param seq    上报序号（用于事件幂等）
     */
    public void evaluate(long userId, double lat, double lon, long seq) {
        long start = System.currentTimeMillis();
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            log.warn("[fence-eval] invalid coordinate, userId={}, lat={}, lon={}", userId, lat, lon);
            throw new IllegalArgumentException("非法经纬度");
        }

        // 1. 用当前格子召回候选围栏（GeoHash 倒排）
        Set<Long> candidateFenceIds = recallCandidates(lat, lon);

        // 2. 逐个精判，得到用户当前真实命中的围栏集合
        Set<Long> hitFences = new HashSet<>();
        for (Long fenceId : candidateFenceIds) {
            try {
                GeoFence fence = fenceRepository.findById(fenceId);
                if (fence != null && fence.getStatus() == 1 && contains(fence, lat, lon)) {
                    hitFences.add(fenceId);
                }
            } catch (Exception e) {
                // 单个围栏精判异常不影响其他围栏
                log.error("[fence-eval] precise judge failed, userId={}, fenceId={}",
                        userId, fenceId, e);
            }
        }

        // 3. 对比上次状态，计算进/出事件
        Set<Long> lastFences = getUserFenceState(userId);
        Set<Long> entered = diff(hitFences, lastFences); // 新进入
        Set<Long> exited = diff(lastFences, hitFences);   // 新离开

        // 4. 更新用户围栏状态
        updateUserFenceState(userId, hitFences);

        // 5. 发布事件（带幂等）
        for (Long fenceId : entered) {
            fireEvent(userId, fenceId, "ENTER", lat, lon, seq);
        }
        for (Long fenceId : exited) {
            fireEvent(userId, fenceId, "EXIT", lat, lon, seq);
        }

        log.info("[fence-eval] done, userId={}, candidates={}, hit={}, enter={}, exit={}, cost={}ms",
                userId, candidateFenceIds.size(), hitFences.size(),
                entered.size(), exited.size(), System.currentTimeMillis() - start);
    }

    /** 召回：当前格子命中的候选围栏 */
    private Set<Long> recallCandidates(double lat, double lon) {
        String cell = GeoHashCodec.encode(lat, lon, FENCE_GEOHASH_PRECISION);
        try {
            Set<String> ids = redisTemplate.opsForSet().members(CELL_FENCE_INDEX + cell);
            if (ids == null || ids.isEmpty()) {
                return Collections.emptySet();
            }
            return ids.stream().map(Long::parseLong).collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("[fence-eval] recall from redis failed, cell={}, degrade to DB", cell, e);
            // 降级：直接从 DB 按格子查倒排
            return fenceRepository.findFenceIdsByCell(cell);
        }
    }

    /**
     * 发布进出事件（幂等）
     *
     * 幂等设计：同一 (userId, fenceId, eventType, seq) 只发一次，
     * 防止上报重试导致重复触发营销/通知（避免用户收到多条重复短信）。
     */
    private void fireEvent(long userId, long fenceId, String eventType,
                           double lat, double lon, long seq) {
        String idemKey = EVENT_IDEMPOTENT + userId + ":" + fenceId + ":" + eventType + ":" + seq;
        Boolean first;
        try {
            first = redisTemplate.opsForValue()
                    .setIfAbsent(idemKey, "1", 300, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[fence-event] idempotent check failed, userId={}, fenceId={}",
                    userId, fenceId, e);
            first = Boolean.TRUE; // Redis 异常时放行
        }
        if (Boolean.FALSE.equals(first)) {
            log.info("[fence-event] duplicated event ignored, userId={}, fenceId={}, type={}",
                    userId, fenceId, eventType);
            return;
        }

        GeoFenceEvent event = new GeoFenceEvent(userId, fenceId, eventType,
                lat, lon, System.currentTimeMillis());
        try {
            kafkaTemplate.send(FENCE_EVENT_TOPIC, String.valueOf(userId), JSON.toJSONString(event));
            log.info("[fence-event] fired, userId={}, fenceId={}, type={}",
                    userId, fenceId, eventType);
        } catch (Exception e) {
            // 发事件失败时回滚幂等标记，允许下次重试
            log.error("[fence-event] publish failed, userId={}, fenceId={}", userId, fenceId, e);
            try {
                redisTemplate.delete(idemKey);
            } catch (Exception ignore) {
                // 回滚失败仅告警
                log.warn("[fence-event] rollback idem key failed, key={}", idemKey);
            }
        }
    }

    /** 圆形/多边形围栏精判 */
    private boolean contains(GeoFence fence, double lat, double lon) {
        if (fence.getType() == FenceType.CIRCLE) {
            double dist = new GeoPoint(fence.getCenterLat(), fence.getCenterLon())
                    .distanceTo(new GeoPoint(lat, lon));
            return dist <= fence.getRadiusMeters();
        } else {
            return isPointInPolygon(lat, lon, fence.getPolygon());
        }
    }

    /**
     * 射线法判断点是否在多边形内
     * 从该点向右发射水平射线，统计与多边形边的交点数，奇数则在内部
     */
    private boolean isPointInPolygon(double lat, double lon, List<GeoPoint> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = polygon.get(i).getLatitude(), xi = polygon.get(i).getLongitude();
            double yj = polygon.get(j).getLatitude(), xj = polygon.get(j).getLongitude();
            boolean intersect = ((yi > lat) != (yj > lat))
                    && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 计算围栏外接矩形覆盖的 GeoHash 格子集合 */
    private Set<String> computeCoveringCells(GeoFence fence) {
        double minLat, maxLat, minLon, maxLon;
        if (fence.getType() == FenceType.CIRCLE) {
            double latOffset = fence.getRadiusMeters() / 111000.0;
            double lonOffset = fence.getRadiusMeters()
                    / (111000.0 * Math.cos(Math.toRadians(fence.getCenterLat())));
            minLat = fence.getCenterLat() - latOffset;
            maxLat = fence.getCenterLat() + latOffset;
            minLon = fence.getCenterLon() - lonOffset;
            maxLon = fence.getCenterLon() + lonOffset;
        } else {
            minLat = fence.getPolygon().stream().mapToDouble(GeoPoint::getLatitude).min().orElse(0);
            maxLat = fence.getPolygon().stream().mapToDouble(GeoPoint::getLatitude).max().orElse(0);
            minLon = fence.getPolygon().stream().mapToDouble(GeoPoint::getLongitude).min().orElse(0);
            maxLon = fence.getPolygon().stream().mapToDouble(GeoPoint::getLongitude).max().orElse(0);
        }

        // 以格子步长枚举外接矩形内的格子（7 位精度格子约 0.00137 度）
        Set<String> cells = new HashSet<>();
        double step = 0.001;
        for (double la = minLat; la <= maxLat; la += step) {
            for (double lo = minLon; lo <= maxLon; lo += step) {
                cells.add(GeoHashCodec.encode(la, lo, FENCE_GEOHASH_PRECISION));
            }
        }
        return cells;
    }

    private void removeFenceFromIndex(long fenceId) {
        GeoFence old = fenceRepository.findById(fenceId);
        if (old != null && old.getCoveringCells() != null) {
            for (String cell : old.getCoveringCells()) {
                redisTemplate.opsForSet().remove(CELL_FENCE_INDEX + cell, String.valueOf(fenceId));
            }
        }
    }

    private Set<Long> getUserFenceState(long userId) {
        try {
            Set<String> members = redisTemplate.opsForSet().members(USER_FENCE_STATE + userId);
            if (members == null) {
                return Collections.emptySet();
            }
            return members.stream().map(Long::parseLong).collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[fence-eval] read user state failed, userId={}", userId, e);
            return Collections.emptySet();
        }
    }

    private void updateUserFenceState(long userId, Set<Long> hitFences) {
        String key = USER_FENCE_STATE + userId;
        try {
            redisTemplate.delete(key);
            if (!hitFences.isEmpty()) {
                redisTemplate.opsForSet().add(key, hitFences.stream()
                        .map(String::valueOf).toArray(String[]::new));
                redisTemplate.expire(key, 1, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            log.error("[fence-eval] update user state failed, userId={}", userId, e);
        }
    }

    private Set<Long> diff(Set<Long> a, Set<Long> b) {
        Set<Long> r = new HashSet<>(a);
        r.removeAll(b);
        return r;
    }
}

/**
 * 围栏事件消费者：驱动通知/营销
 */
@Component
public class GeoFenceEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(GeoFenceEventConsumer.class);

    @Autowired
    private MarketingService marketingService;
    @Autowired
    private NotifyService notifyService;

    /**
     * 消费围栏事件
     * 幂等：营销发放接口自身按 (userId, fenceId, eventType) 做业务幂等，
     * 消费者重复消费不会重复发券。
     */
    @KafkaListener(topics = "geo-fence-event", groupId = "fence-action")
    public void consume(String message) {
        GeoFenceEvent event;
        try {
            event = JSON.parseObject(message, GeoFenceEvent.class);
        } catch (Exception e) {
            log.error("[fence-action] parse failed, message={}", message, e);
            return; // 脏消息直接丢弃，避免无限重试
        }
        try {
            if ("ENTER".equals(event.getEventType())) {
                // 进入围栏：触发到店营销/推送优惠券
                marketingService.onEnterFence(event.getUserId(), event.getFenceId());
                notifyService.push(event.getUserId(), "欢迎光临，专属优惠已送达");
                log.info("[fence-action] enter action done, userId={}, fenceId={}",
                        event.getUserId(), event.getFenceId());
            } else if ("EXIT".equals(event.getEventType())) {
                marketingService.onExitFence(event.getUserId(), event.getFenceId());
                log.info("[fence-action] exit action done, userId={}, fenceId={}",
                        event.getUserId(), event.getFenceId());
            }
        } catch (Exception e) {
            // 抛出交由 Kafka 重试，业务幂等保证重复安全
            log.error("[fence-action] handle event failed, userId={}, fenceId={}, type={}",
                    event.getUserId(), event.getFenceId(), event.getEventType(), e);
            throw new LbsException("围栏事件处理失败", e);
        }
    }
}

/**
 * 地理围栏定义
 */
@Data
public class GeoFence {
    private Long fenceId;
    private String name;
    private FenceType type;         // CIRCLE / POLYGON
    private double centerLat;       // 圆形：圆心纬度
    private double centerLon;       // 圆形：圆心经度
    private double radiusMeters;    // 圆形：半径（米）
    private List<GeoPoint> polygon; // 多边形：顶点列表
    private Set<String> coveringCells; // 覆盖的 GeoHash 格子（用于索引清理）
    private Integer status;         // 1-生效 0-失效
}

/**
 * 围栏类型
 */
public enum FenceType {
    CIRCLE, POLYGON
}

/**
 * 围栏进出事件
 */
@Data
@AllArgsConstructor
public class GeoFenceEvent {
    private long userId;
    private long fenceId;
    private String eventType;   // ENTER / EXIT
    private double latitude;
    private double longitude;
    private long timestamp;
}
```

#### 7.3.3 关键设计说明

| 关注点 | 实现方式 |
|--------|---------|
| 召回加速 | 预计算围栏覆盖格子建"格子->围栏"倒排，避免遍历全量围栏 |
| 精判 | 圆形用距离判定，多边形用射线法（奇偶交点） |
| 状态跃迁 | 对比上次命中集合，仅在 ENTER/EXIT 跃迁时发事件，避免持续触发 |
| 事件幂等 | (userId,fenceId,type,seq) SETNX 去重，防止重复发券/推送 |
| 配置幂等 | 重配时先删旧格子索引再重建，结果与配置次数无关 |
| 异常隔离 | 单围栏精判异常、召回降级到 DB、发事件失败回滚幂等标记 |

### 7.4 三个案例的共性总结

| 维度 | 案例一（附近搜索） | 案例二（骑手追踪） | 案例三（地理围栏） |
|------|------------------|------------------|------------------|
| 幂等手段 | 上报序号 SETNX | GPS 时间戳水位线 | 事件四元组 SETNX |
| 异常降级 | Redis -> DB(SQL矩形) | 更新失败仍落轨迹 | 召回 Redis -> DB |
| 日志埋点 | 阶段耗时+候选/命中 | 全链路+丢弃原因 | 候选/命中/进出数 |
| 核心存储 | Redis Geo + ZSet游标 | Redis Geo + HBase | Redis 倒排 + DB |
| 事件驱动 | 无（同步返回） | Kafka 轨迹 | Kafka 进出事件 |

三个案例贯穿了同一套工程方法论：

1. **幂等前置**：所有"写"和"触发"动作，入口先做幂等判定，弱网重试是常态而非异常。
2. **异常不裸奔**：外部依赖（Redis/Kafka/DB）调用一律 try-catch，区分"可降级"与"必须失败"，主链路失败要能降级或明确抛出。
3. **日志可回溯**：每个阶段打点耗时与关键计数，异常带上下文（userId/riderId/坐标），保证线上问题可快速定位。
4. **粗筛 + 精算**：无论搜索还是围栏判定，都遵循"GeoHash 格子粗召回 -> 精确计算二次过滤"的两段式，兼顾性能与精度。