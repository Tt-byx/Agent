package com.scenic.ai.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P5 · 景区智能导游的工具集（5 个）。
 *
 * 设计原则：
 *   1. **description 写「什么时候调用」而不只是「这是干什么的」** —— 模型靠它做工具选择，
 *      写清触发场景的命中率明显更高。
 *   2. **参数接受中文取值** —— 实测 deepseek-flash 会填「成人」而不是枚举名，description 里
 *      必须把中文可选项列全，实现里再做归一化兜底。
 *   3. **每个工具都打 INFO 日志** —— 这是验证「模型选对了工具、填对了参数」的直接证据。
 *   4. 工具只做「模型自己算不出来」的事：实时数据、精确计算、规则组合、时间相关判断。
 */
@Component
@Slf4j
public class ScenicTools {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 支持的城市（Open-Meteo 需要经纬度） */
    private static final Map<String, double[]> CITY_COORDS = Map.of(
            "无锡", new double[]{31.4912, 120.3119},
            "上海", new double[]{31.2304, 121.4737},
            "南京", new double[]{32.0603, 118.7969},
            "苏州", new double[]{31.2989, 120.5853},
            "杭州", new double[]{30.2741, 120.1551},
            "北京", new double[]{39.9042, 116.4074}
    );

    // ============================================================
    // 工具 1：实时天气
    // ============================================================

    @Tool("查询指定城市的实时天气，返回温度、天气状况、湿度和风力，并给出出行建议。"
            + "当游客询问天气怎么样、要不要带伞、穿什么衣服、适不适合出门时调用。")
    public String queryWeather(@P("城市名称，例如：无锡、上海、苏州") String city) {
        String normalized = city == null ? "" : city.replace("市", "").strip();
        double[] coords = CITY_COORDS.get(normalized);
        if (coords == null) {
            String result = "暂不支持查询「" + city + "」的天气，目前支持："
                    + String.join("、", CITY_COORDS.keySet()) + "。";
            log.info("[TOOL] queryWeather(city={}) -> {}", city, result);
            return result;
        }

        String result;
        try {
            String url = String.format(
                    "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                            + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m",
                    coords[0], coords[1]);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode current = objectMapper.readTree(response.body()).path("current");
                double temp = current.path("temperature_2m").asDouble();
                int humidity = current.path("relative_humidity_2m").asInt();
                double wind = current.path("wind_speed_10m").asDouble();
                String condition = weatherCodeToText(current.path("weather_code").asInt());

                result = String.format("%s当前天气：%s，气温 %.1f℃，湿度 %d%%，风速 %.1f km/h。%s",
                        normalized, condition, temp, humidity, wind, travelAdvice(condition, temp));
            } else {
                result = normalized + "的天气数据暂时获取不到（接口返回 " + response.statusCode() + "）。";
            }
        } catch (Exception e) {
            log.warn("天气接口调用失败: {}", e.getMessage());
            result = normalized + "的实时天气暂时查询不到，请以天气预报为准。";
        }

        log.info("[TOOL] queryWeather(city={}) -> {}", city, result);
        return result;
    }

    private String weatherCodeToText(int code) {
        if (code == 0) return "晴";
        if (code <= 3) return "多云";
        if (code <= 48) return "有雾";
        if (code <= 57) return "毛毛雨";
        if (code <= 67) return "有雨";
        if (code <= 77) return "有雪";
        if (code <= 82) return "阵雨";
        return "雷雨";
    }

    private String travelAdvice(String condition, double temp) {
        if (condition.contains("雨")) {
            return "建议携带雨具，景区多为户外步行路段。";
        }
        if (temp >= 32) {
            return "气温较高，注意防晒补水，建议上午或傍晚游览。";
        }
        if (temp <= 5) {
            return "气温较低，注意保暖，建议穿厚外套。";
        }
        return "天气适合户外游览。";
    }

    // ============================================================
    // 工具 2：门票价格计算
    // ============================================================

    @Tool("计算景区门票总价。根据游客类型和人数算出应付金额，并说明优惠依据。"
            + "当游客询问门票多少钱、我们有几个人要花多少钱、有没有优惠时调用。"
            + "票价规则：成人票210元；半价票105元（6-18周岁未成年人、全日制本科及以下学生、60-69周岁老人）；"
            + "免票0元（6周岁以下或1.4米以下儿童、70周岁以上老人、现役军人、残疾人）。")
    public String calculateTicketPrice(
            @P("游客类型，可取：成人、学生、老人、儿童、军人或残疾人") String visitorType,
            @P("人数，正整数，例如 3") int count) {
        if (count <= 0) {
            return "人数需要是正整数。";
        }

        String type = visitorType == null ? "成人" : visitorType.strip();
        int unitPrice;
        String basis;

        if (type.contains("成人")) {
            unitPrice = 210;
            basis = "成人票210元（18周岁以上）";
        } else if (type.contains("学生") || type.contains("未成年")) {
            unitPrice = 105;
            basis = "半价票105元（6-18周岁未成年人、全日制本科及以下学生）";
        } else if (type.contains("老人") || type.contains("老年")) {
            unitPrice = 105;
            basis = "半价票105元（60-69周岁老人；70周岁以上属免票范围）";
        } else if (type.contains("儿童") || type.contains("小孩")) {
            unitPrice = 0;
            basis = "免票（6周岁以下或1.4米以下儿童）";
        } else if (type.contains("军人") || type.contains("残疾")) {
            unitPrice = 0;
            basis = "免票（现役军人、残疾人）";
        } else {
            unitPrice = 210;
            basis = "按成人票210元估算（未识别到「" + type + "」对应的优惠类型）";
        }

        int total = unitPrice * count;
        String result = String.format("%s %d 人：%s，合计 %d 元。", type, count, basis, total);
        if (unitPrice > 0) {
            result += "另外网购联票 225 元含门票 + 观光车（无限次乘坐），人多时更划算。";
        }

        log.info("[TOOL] calculateTicketPrice(type={}, count={}) -> {}", visitorType, count, result);
        return result;
    }

    // ============================================================
    // 工具 3：下一场演出时间（依赖当前时间）
    // ============================================================

    private static final Map<String, LocalTime[]> SHOW_SCHEDULE = Map.of(
            "九龙灌浴", new LocalTime[]{
                    LocalTime.of(10, 0), LocalTime.of(11, 30),
                    LocalTime.of(13, 30), LocalTime.of(15, 0)},
            "吉祥颂", new LocalTime[]{
                    LocalTime.of(10, 35), LocalTime.of(11, 30),
                    LocalTime.of(14, 0), LocalTime.of(16, 0)}
    );

    @Tool("查询景区演出的下一场开始时间（基于当前时间实时计算），返回剩余等待时长。"
            + "可查询的演出：九龙灌浴、吉祥颂。"
            + "当游客询问表演什么时候开始、几点有演出、现在过去还赶不赶得上时调用。")
    public String getNextShowTime(@P("演出名称，可取：九龙灌浴 或 吉祥颂") String showName) {
        String name = showName == null ? "" : showName.strip();
        LocalTime[] schedule = SHOW_SCHEDULE.get(name);

        if (schedule == null) {
            for (Map.Entry<String, LocalTime[]> entry : SHOW_SCHEDULE.entrySet()) {
                if (name.contains(entry.getKey()) || entry.getKey().contains(name)) {
                    schedule = entry.getValue();
                    name = entry.getKey();
                    break;
                }
            }
        }
        if (schedule == null) {
            String result = "没有找到「" + showName + "」的演出信息，目前可查询：九龙灌浴、吉祥颂。";
            log.info("[TOOL] getNextShowTime(name={}) -> {}", showName, result);
            return result;
        }

        LocalTime now = LocalTime.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        LocalTime next = null;
        for (LocalTime show : schedule) {
            if (show.isAfter(now)) {
                next = show;
                break;
            }
        }

        String scheduleText = schedule[0].format(fmt);
        for (int i = 1; i < schedule.length; i++) {
            scheduleText += "、" + schedule[i].format(fmt);
        }

        String result;
        if (next == null) {
            result = String.format("%s 今天的场次（%s）都已经结束了。现在时间是 %s，可以安排明天再看。",
                    name, scheduleText, now.format(fmt));
        } else {
            long minutes = Duration.between(now, next).toMinutes();
            result = String.format("%s 今天还有 %d 场（%s）。现在 %s，下一场是 %s，还有约 %d 分钟。",
                    name, schedule.length, scheduleText, now.format(fmt), next.format(fmt), minutes);
        }

        log.info("[TOOL] getNextShowTime(name={}) -> {}", showName, result);
        return result;
    }

    // ============================================================
    // 工具 4：路线推荐（规则组合）
    // ============================================================

    @Tool("根据游客的兴趣偏好、可用时长和同行人群，组合推荐一条具体的游览路线（含游览顺序与重点）。"
            + "当游客要求推荐路线、帮忙规划行程、问「怎么玩」「带小孩怎么走」时调用。"
            + "注意：这是基于规则的行程编排，与知识库里的固定路线介绍不同。")
    public String recommendRoute(
            @P("兴趣偏好，可取：历史文化、自然风光、亲子、摄影、美食禅意") String interest,
            @P("可用时长，可取：2小时、半天、全天") String duration,
            @P("同行人群，可取：独自、情侣、带小孩、带老人、朋友结伴") String companion) {

        String it = interest == null ? "历史文化" : interest.strip();
        String du = duration == null ? "半天" : duration.strip();
        String cp = companion == null ? "独自" : companion.strip();

        StringBuilder route = new StringBuilder();
        route.append(cp).append("、").append(du).append("、").append(it).append("偏好，推荐这样走：");

        // 核心节点顺序
        if (it.contains("亲子") || cp.contains("小孩")) {
            route.append("南门入园 → 九龙灌浴（看表演）→ 佛手广场（摸天下第一掌）→ 百子戏弥勒 → 梵宫 → 出口。");
        } else if (it.contains("摄影")) {
            route.append("南门入园 → 灵山大照壁（湖光壁影）→ 菩提大道 → 九龙灌浴 → 灵山大佛登顶俯瞰太湖 → 梵宫 → 出口。");
        } else if (it.contains("自然")) {
            route.append("南门入园 → 佛足坛 → 九龙灌浴 → 菩提大道（太湖风光）→ 灵山大佛登顶 → 曼飞龙塔 → 灵山精舍 → 出口。");
        } else if (it.contains("美食") || it.contains("禅意")) {
            route.append("南门入园 → 五明桥 → 祥符禅寺（撞钟祈福）→ 灵山大佛 → 梵宫 → 灵山精舍（素斋）→ 出口。");
        } else {
            route.append("南门入园 → 灵山大照壁 → 佛手广场 → 祥符禅寺 → 灵山大佛 → 灵山梵宫 → 五印坛城 → 出口。");
        }

        // 时长调整
        if (du.contains("2")) {
            route.append(" 时间只有 2 小时，建议只保留灵山大佛 + 灵山梵宫两个核心点，九龙灌浴看最近一场就走。");
        } else if (du.contains("全天")) {
            route.append(" 全天时间充裕，可以把五印坛城、曼飞龙塔、灵山精舍都走完，中午在梵宫用素斋。");
        } else {
            route.append(" 半天时间建议保留 4-5 个核心点，中途在菩提大道歇脚。");
        }

        // 人群提示
        if (cp.contains("老人")) {
            route.append(" 有老人同行：景区步道较长，建议买观光车联票（225元），并避开 216 级登云道，改走平缓路线。");
        } else if (cp.contains("小孩")) {
            route.append(" 带孩子注意：九龙灌浴的喷泉表演和百子戏弥勒最受欢迎，建议提前 10 分钟占位。");
        } else if (cp.contains("情侣")) {
            route.append(" 两个人推荐傍晚去大佛平台看太湖日落，光线最好。");
        }

        String result = route.toString();
        log.info("[TOOL] recommendRoute(interest={}, duration={}, companion={}) -> {}",
                interest, duration, companion, result);
        return result;
    }

    // ============================================================
    // 工具 5：景点实时状态与拥挤度
    // ============================================================

    private static final Map<String, String> SPOT_NOTES = Map.of(
            "灵山大佛", "露天区域，可登顶抱佛脚；216级登云道较耗体力。",
            "灵山梵宫", "室内场馆，部分区域禁止拍照；圣坛演出需提前入场。",
            "九龙灌浴", "露天动态景观，表演场次固定，结束后可在两侧接祈福圣水。",
            "五印坛城", "室内为主，转经廊可亲手转动经筒；建议顺时针右绕。",
            "祥符禅寺", "千年古刹，有千年银杏与六角古井；可参与撞钟祈福。"
    );

    @Tool("查询指定景点的当前开放状态、拥挤度预估和参观注意事项。"
            + "当游客询问某个景点现在能不能去、人多不多、要不要排队、有什么要注意的时调用。")
    public String checkSpotStatus(@P("景点名称，例如：灵山大佛、灵山梵宫、九龙灌浴") String spotName) {
        String name = spotName == null ? "" : spotName.strip();

        String matched = null;
        for (String key : SPOT_NOTES.keySet()) {
            if (name.contains(key) || key.contains(name)) {
                matched = key;
                break;
            }
        }
        if (matched == null) {
            String result = "没有查到「" + name + "」的实时状态，目前可查询：" + String.join("、", SPOT_NOTES.keySet()) + "。";
            log.info("[TOOL] checkSpotStatus(name={}) -> {}", spotName, result);
            return result;
        }

        LocalTime now = LocalTime.now();
        DayOfWeek day = LocalDate.now().getDayOfWeek();
        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;

        int hour = now.getHour();
        String crowd;
        if (hour < 9) {
            crowd = "当前游客很少（开园前后），适合安静参观与拍照";
        } else if (hour < 11) {
            crowd = "当前游客较多（上午高峰），建议错峰";
        } else if (hour < 14) {
            crowd = "当前游客很多（午间高峰），热门点位可能需要排队";
        } else if (hour < 17) {
            crowd = weekend ? "当前游客较多（周末下午）" : "当前游客中等（下午时段），相对舒适";
        } else {
            crowd = "当前游客较少（临近闭园）";
        }

        String result = String.format("%s：正常开放。%s%s。注意事项：%s",
                matched, crowd, weekend ? "，今天是周末" : "", SPOT_NOTES.get(matched));

        log.info("[TOOL] checkSpotStatus(name={}) -> {}", spotName, result);
        return result;
    }
}
