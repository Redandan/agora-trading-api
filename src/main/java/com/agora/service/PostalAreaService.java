package com.agora.service;

import com.agora.dto.TaiwanPostalArea;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 台灣郵遞區號服務
 * 處理郵遞區號相關的業務邏輯
 */
@Service
public class PostalAreaService {
    private List<TaiwanPostalArea> postalAreas;
    private List<String> cityList;
    private Map<String, List<TaiwanPostalArea>> postalCodeMap;
    private Map<String, List<TaiwanPostalArea>> cityMap;
    private Map<String, List<TaiwanPostalArea>> districtMap;

    @PostConstruct
    public void init() {
        postalAreas = new ArrayList<>();

        // 台北市
        postalAreas.add(new TaiwanPostalArea("100", "台北市", "中正區", true));
        postalAreas.add(new TaiwanPostalArea("103", "台北市", "大同區", true));
        postalAreas.add(new TaiwanPostalArea("104", "台北市", "中山區", true));
        postalAreas.add(new TaiwanPostalArea("105", "台北市", "松山區", true));
        postalAreas.add(new TaiwanPostalArea("106", "台北市", "大安區", true));
        postalAreas.add(new TaiwanPostalArea("108", "台北市", "萬華區", true));
        postalAreas.add(new TaiwanPostalArea("110", "台北市", "信義區", true));
        postalAreas.add(new TaiwanPostalArea("111", "台北市", "士林區", true));
        postalAreas.add(new TaiwanPostalArea("112", "台北市", "北投區", true));
        postalAreas.add(new TaiwanPostalArea("114", "台北市", "內湖區", true));
        postalAreas.add(new TaiwanPostalArea("115", "台北市", "南港區", true));
        postalAreas.add(new TaiwanPostalArea("116", "台北市", "文山區", true));

        // 新北市
        postalAreas.add(new TaiwanPostalArea("220", "新北市", "板橋區", true));
        postalAreas.add(new TaiwanPostalArea("241", "新北市", "三重區", true));
        postalAreas.add(new TaiwanPostalArea("235", "新北市", "中和區", true));
        postalAreas.add(new TaiwanPostalArea("234", "新北市", "永和區", true));
        postalAreas.add(new TaiwanPostalArea("242", "新北市", "新莊區", true));
        postalAreas.add(new TaiwanPostalArea("231", "新北市", "新店區", true));
        postalAreas.add(new TaiwanPostalArea("236", "新北市", "土城區", true));
        postalAreas.add(new TaiwanPostalArea("247", "新北市", "蘆洲區", true));
        postalAreas.add(new TaiwanPostalArea("238", "新北市", "樹林區", true));
        postalAreas.add(new TaiwanPostalArea("221", "新北市", "汐止區", true));
        postalAreas.add(new TaiwanPostalArea("239", "新北市", "鶯歌區", true));
        postalAreas.add(new TaiwanPostalArea("237", "新北市", "三峽區", true));
        postalAreas.add(new TaiwanPostalArea("251", "新北市", "淡水區", true));
        postalAreas.add(new TaiwanPostalArea("224", "新北市", "瑞芳區", true));
        postalAreas.add(new TaiwanPostalArea("248", "新北市", "五股區", true));
        postalAreas.add(new TaiwanPostalArea("243", "新北市", "泰山區", true));
        postalAreas.add(new TaiwanPostalArea("244", "新北市", "林口區", true));
        postalAreas.add(new TaiwanPostalArea("222", "新北市", "深坑區", true));
        postalAreas.add(new TaiwanPostalArea("223", "新北市", "石碇區", true));
        postalAreas.add(new TaiwanPostalArea("232", "新北市", "坪林區", true));
        postalAreas.add(new TaiwanPostalArea("252", "新北市", "三芝區", true));
        postalAreas.add(new TaiwanPostalArea("253", "新北市", "石門區", true));
        postalAreas.add(new TaiwanPostalArea("249", "新北市", "八里區", true));
        postalAreas.add(new TaiwanPostalArea("226", "新北市", "平溪區", true));
        postalAreas.add(new TaiwanPostalArea("227", "新北市", "雙溪區", true));
        postalAreas.add(new TaiwanPostalArea("228", "新北市", "貢寮區", true));
        postalAreas.add(new TaiwanPostalArea("208", "新北市", "金山區", true));
        postalAreas.add(new TaiwanPostalArea("207", "新北市", "萬里區", true));
        postalAreas.add(new TaiwanPostalArea("233", "新北市", "烏來區", true));

        // 桃園市
        postalAreas.add(new TaiwanPostalArea("330", "桃園市", "桃園區", true));
        postalAreas.add(new TaiwanPostalArea("320", "桃園市", "中壢區", true));
        postalAreas.add(new TaiwanPostalArea("324", "桃園市", "平鎮區", true));
        postalAreas.add(new TaiwanPostalArea("334", "桃園市", "八德區", true));
        postalAreas.add(new TaiwanPostalArea("326", "桃園市", "楊梅區", true));
        postalAreas.add(new TaiwanPostalArea("338", "桃園市", "蘆竹區", true));
        postalAreas.add(new TaiwanPostalArea("335", "桃園市", "大溪區", true));
        postalAreas.add(new TaiwanPostalArea("325", "桃園市", "龍潭區", true));
        postalAreas.add(new TaiwanPostalArea("333", "桃園市", "龜山區", true));
        postalAreas.add(new TaiwanPostalArea("337", "桃園市", "大園區", true));
        postalAreas.add(new TaiwanPostalArea("328", "桃園市", "觀音區", true));
        postalAreas.add(new TaiwanPostalArea("327", "桃園市", "新屋區", true));
        postalAreas.add(new TaiwanPostalArea("336", "桃園市", "復興區", true));

        // 台中市
        postalAreas.add(new TaiwanPostalArea("400", "台中市", "中區", true));
        postalAreas.add(new TaiwanPostalArea("401", "台中市", "東區", true));
        postalAreas.add(new TaiwanPostalArea("402", "台中市", "南區", true));
        postalAreas.add(new TaiwanPostalArea("403", "台中市", "西區", true));
        postalAreas.add(new TaiwanPostalArea("404", "台中市", "北區", true));
        postalAreas.add(new TaiwanPostalArea("407", "台中市", "西屯區", true));
        postalAreas.add(new TaiwanPostalArea("408", "台中市", "南屯區", true));
        postalAreas.add(new TaiwanPostalArea("406", "台中市", "北屯區", true));
        postalAreas.add(new TaiwanPostalArea("420", "台中市", "豐原區", true));
        postalAreas.add(new TaiwanPostalArea("423", "台中市", "東勢區", true));
        postalAreas.add(new TaiwanPostalArea("437", "台中市", "大甲區", true));
        postalAreas.add(new TaiwanPostalArea("436", "台中市", "清水區", true));
        postalAreas.add(new TaiwanPostalArea("433", "台中市", "沙鹿區", true));
        postalAreas.add(new TaiwanPostalArea("435", "台中市", "梧棲區", true));
        postalAreas.add(new TaiwanPostalArea("421", "台中市", "后里區", true));
        postalAreas.add(new TaiwanPostalArea("429", "台中市", "神岡區", true));
        postalAreas.add(new TaiwanPostalArea("427", "台中市", "潭子區", true));
        postalAreas.add(new TaiwanPostalArea("428", "台中市", "大雅區", true));
        postalAreas.add(new TaiwanPostalArea("426", "台中市", "新社區", true));
        postalAreas.add(new TaiwanPostalArea("422", "台中市", "石岡區", true));
        postalAreas.add(new TaiwanPostalArea("438", "台中市", "外埔區", true));
        postalAreas.add(new TaiwanPostalArea("439", "台中市", "大安區", true));
        postalAreas.add(new TaiwanPostalArea("414", "台中市", "烏日區", true));
        postalAreas.add(new TaiwanPostalArea("432", "台中市", "大肚區", true));
        postalAreas.add(new TaiwanPostalArea("434", "台中市", "龍井區", true));
        postalAreas.add(new TaiwanPostalArea("413", "台中市", "霧峰區", true));
        postalAreas.add(new TaiwanPostalArea("411", "台中市", "太平區", true));
        postalAreas.add(new TaiwanPostalArea("412", "台中市", "大里區", true));
        postalAreas.add(new TaiwanPostalArea("424", "台中市", "和平區", true));

        // 台南市
        postalAreas.add(new TaiwanPostalArea("700", "台南市", "中西區", true));
        postalAreas.add(new TaiwanPostalArea("701", "台南市", "東區", true));
        postalAreas.add(new TaiwanPostalArea("702", "台南市", "南區", true));
        postalAreas.add(new TaiwanPostalArea("704", "台南市", "北區", true));
        postalAreas.add(new TaiwanPostalArea("708", "台南市", "安平區", true));
        postalAreas.add(new TaiwanPostalArea("709", "台南市", "安南區", true));
        postalAreas.add(new TaiwanPostalArea("710", "台南市", "永康區", true));
        postalAreas.add(new TaiwanPostalArea("711", "台南市", "歸仁區", true));
        postalAreas.add(new TaiwanPostalArea("712", "台南市", "新化區", true));
        postalAreas.add(new TaiwanPostalArea("713", "台南市", "左鎮區", true));
        postalAreas.add(new TaiwanPostalArea("714", "台南市", "玉井區", true));
        postalAreas.add(new TaiwanPostalArea("715", "台南市", "楠西區", true));
        postalAreas.add(new TaiwanPostalArea("716", "台南市", "南化區", true));
        postalAreas.add(new TaiwanPostalArea("717", "台南市", "仁德區", true));
        postalAreas.add(new TaiwanPostalArea("718", "台南市", "關廟區", true));
        postalAreas.add(new TaiwanPostalArea("719", "台南市", "龍崎區", true));
        postalAreas.add(new TaiwanPostalArea("720", "台南市", "官田區", true));
        postalAreas.add(new TaiwanPostalArea("721", "台南市", "麻豆區", true));
        postalAreas.add(new TaiwanPostalArea("722", "台南市", "佳里區", true));
        postalAreas.add(new TaiwanPostalArea("723", "台南市", "西港區", true));
        postalAreas.add(new TaiwanPostalArea("724", "台南市", "七股區", true));
        postalAreas.add(new TaiwanPostalArea("725", "台南市", "將軍區", true));
        postalAreas.add(new TaiwanPostalArea("726", "台南市", "學甲區", true));
        postalAreas.add(new TaiwanPostalArea("727", "台南市", "北門區", true));
        postalAreas.add(new TaiwanPostalArea("730", "台南市", "新營區", true));
        postalAreas.add(new TaiwanPostalArea("731", "台南市", "後壁區", true));
        postalAreas.add(new TaiwanPostalArea("732", "台南市", "白河區", true));
        postalAreas.add(new TaiwanPostalArea("733", "台南市", "東山區", true));
        postalAreas.add(new TaiwanPostalArea("734", "台南市", "六甲區", true));
        postalAreas.add(new TaiwanPostalArea("735", "台南市", "下營區", true));
        postalAreas.add(new TaiwanPostalArea("736", "台南市", "柳營區", true));
        postalAreas.add(new TaiwanPostalArea("737", "台南市", "鹽水區", true));
        postalAreas.add(new TaiwanPostalArea("741", "台南市", "善化區", true));
        postalAreas.add(new TaiwanPostalArea("742", "台南市", "大內區", true));
        postalAreas.add(new TaiwanPostalArea("743", "台南市", "山上區", true));
        postalAreas.add(new TaiwanPostalArea("744", "台南市", "新市區", true));
        postalAreas.add(new TaiwanPostalArea("745", "台南市", "安定區", true));

        // 高雄市
        postalAreas.add(new TaiwanPostalArea("811", "高雄市", "楠梓區", true));
        postalAreas.add(new TaiwanPostalArea("813", "高雄市", "左營區", true));
        postalAreas.add(new TaiwanPostalArea("804", "高雄市", "鼓山區", true));
        postalAreas.add(new TaiwanPostalArea("807", "高雄市", "三民區", true));
        postalAreas.add(new TaiwanPostalArea("803", "高雄市", "鹽埕區", true));
        postalAreas.add(new TaiwanPostalArea("801", "高雄市", "前金區", true));
        postalAreas.add(new TaiwanPostalArea("800", "高雄市", "新興區", true));
        postalAreas.add(new TaiwanPostalArea("802", "高雄市", "苓雅區", true));
        postalAreas.add(new TaiwanPostalArea("806", "高雄市", "前鎮區", true));
        postalAreas.add(new TaiwanPostalArea("805", "高雄市", "旗津區", true));
        postalAreas.add(new TaiwanPostalArea("812", "高雄市", "小港區", true));
        postalAreas.add(new TaiwanPostalArea("830", "高雄市", "鳳山區", true));
        postalAreas.add(new TaiwanPostalArea("832", "高雄市", "林園區", true));
        postalAreas.add(new TaiwanPostalArea("831", "高雄市", "大寮區", true));
        postalAreas.add(new TaiwanPostalArea("840", "高雄市", "大樹區", true));
        postalAreas.add(new TaiwanPostalArea("815", "高雄市", "大社區", true));
        postalAreas.add(new TaiwanPostalArea("814", "高雄市", "仁武區", true));
        postalAreas.add(new TaiwanPostalArea("833", "高雄市", "鳥松區", true));
        postalAreas.add(new TaiwanPostalArea("820", "高雄市", "岡山區", true));
        postalAreas.add(new TaiwanPostalArea("825", "高雄市", "橋頭區", true));
        postalAreas.add(new TaiwanPostalArea("824", "高雄市", "燕巢區", true));
        postalAreas.add(new TaiwanPostalArea("823", "高雄市", "田寮區", true));
        postalAreas.add(new TaiwanPostalArea("822", "高雄市", "阿蓮區", true));
        postalAreas.add(new TaiwanPostalArea("821", "高雄市", "路竹區", true));
        postalAreas.add(new TaiwanPostalArea("829", "高雄市", "湖內區", true));
        postalAreas.add(new TaiwanPostalArea("852", "高雄市", "茄萣區", true));
        postalAreas.add(new TaiwanPostalArea("828", "高雄市", "永安區", true));
        postalAreas.add(new TaiwanPostalArea("827", "高雄市", "彌陀區", true));
        postalAreas.add(new TaiwanPostalArea("826", "高雄市", "梓官區", true));
        postalAreas.add(new TaiwanPostalArea("842", "高雄市", "旗山區", true));
        postalAreas.add(new TaiwanPostalArea("843", "高雄市", "美濃區", true));
        postalAreas.add(new TaiwanPostalArea("844", "高雄市", "六龜區", true));
        postalAreas.add(new TaiwanPostalArea("847", "高雄市", "甲仙區", true));
        postalAreas.add(new TaiwanPostalArea("846", "高雄市", "杉林區", true));
        postalAreas.add(new TaiwanPostalArea("845", "高雄市", "內門區", true));
        postalAreas.add(new TaiwanPostalArea("851", "高雄市", "茂林區", true));
        postalAreas.add(new TaiwanPostalArea("848", "高雄市", "桃源區", true));
        postalAreas.add(new TaiwanPostalArea("849", "高雄市", "那瑪夏區", true));

        // 基隆市
        postalAreas.add(new TaiwanPostalArea("200", "基隆市", "仁愛區", true));
        postalAreas.add(new TaiwanPostalArea("201", "基隆市", "信義區", true));
        postalAreas.add(new TaiwanPostalArea("202", "基隆市", "中正區", true));
        postalAreas.add(new TaiwanPostalArea("203", "基隆市", "中山區", true));
        postalAreas.add(new TaiwanPostalArea("204", "基隆市", "安樂區", true));
        postalAreas.add(new TaiwanPostalArea("205", "基隆市", "暖暖區", true));
        postalAreas.add(new TaiwanPostalArea("206", "基隆市", "七堵區", true));

        // 新竹市
        postalAreas.add(new TaiwanPostalArea("300", "新竹市", "東區", true));
        postalAreas.add(new TaiwanPostalArea("300", "新竹市", "北區", true));
        postalAreas.add(new TaiwanPostalArea("300", "新竹市", "香山區", true));

        // 嘉義市
        postalAreas.add(new TaiwanPostalArea("600", "嘉義市", "東區", true));
        postalAreas.add(new TaiwanPostalArea("600", "嘉義市", "西區", true));

        // 宜蘭縣
        postalAreas.add(new TaiwanPostalArea("260", "宜蘭縣", "宜蘭市", true));
        postalAreas.add(new TaiwanPostalArea("263", "宜蘭縣", "壯圍鄉", true));
        postalAreas.add(new TaiwanPostalArea("261", "宜蘭縣", "頭城鎮", true));
        postalAreas.add(new TaiwanPostalArea("262", "宜蘭縣", "礁溪鄉", true));
        postalAreas.add(new TaiwanPostalArea("264", "宜蘭縣", "員山鄉", true));
        postalAreas.add(new TaiwanPostalArea("265", "宜蘭縣", "羅東鎮", true));
        postalAreas.add(new TaiwanPostalArea("266", "宜蘭縣", "三星鄉", true));
        postalAreas.add(new TaiwanPostalArea("268", "宜蘭縣", "五結鄉", true));
        postalAreas.add(new TaiwanPostalArea("269", "宜蘭縣", "冬山鄉", true));
        postalAreas.add(new TaiwanPostalArea("270", "宜蘭縣", "蘇澳鎮", true));
        postalAreas.add(new TaiwanPostalArea("272", "宜蘭縣", "南澳鄉", true));
        postalAreas.add(new TaiwanPostalArea("267", "宜蘭縣", "大同鄉", true));

        // 新竹縣
        postalAreas.add(new TaiwanPostalArea("302", "新竹縣", "竹北市", true));
        postalAreas.add(new TaiwanPostalArea("303", "新竹縣", "湖口鄉", true));
        postalAreas.add(new TaiwanPostalArea("304", "新竹縣", "新豐鄉", true));
        postalAreas.add(new TaiwanPostalArea("305", "新竹縣", "新埔鎮", true));
        postalAreas.add(new TaiwanPostalArea("306", "新竹縣", "關西鎮", true));
        postalAreas.add(new TaiwanPostalArea("307", "新竹縣", "芎林鄉", true));
        postalAreas.add(new TaiwanPostalArea("308", "新竹縣", "寶山鄉", true));
        postalAreas.add(new TaiwanPostalArea("310", "新竹縣", "竹東鎮", true));
        postalAreas.add(new TaiwanPostalArea("311", "新竹縣", "五峰鄉", true));
        postalAreas.add(new TaiwanPostalArea("312", "新竹縣", "橫山鄉", true));
        postalAreas.add(new TaiwanPostalArea("313", "新竹縣", "尖石鄉", true));
        postalAreas.add(new TaiwanPostalArea("314", "新竹縣", "北埔鄉", true));
        postalAreas.add(new TaiwanPostalArea("315", "新竹縣", "峨眉鄉", true));

        // 苗栗縣
        postalAreas.add(new TaiwanPostalArea("350", "苗栗縣", "竹南鎮", true));
        postalAreas.add(new TaiwanPostalArea("351", "苗栗縣", "頭份市", true));
        postalAreas.add(new TaiwanPostalArea("352", "苗栗縣", "三灣鄉", true));
        postalAreas.add(new TaiwanPostalArea("353", "苗栗縣", "南庄鄉", true));
        postalAreas.add(new TaiwanPostalArea("354", "苗栗縣", "獅潭鄉", true));
        postalAreas.add(new TaiwanPostalArea("356", "苗栗縣", "後龍鎮", true));
        postalAreas.add(new TaiwanPostalArea("357", "苗栗縣", "通霄鎮", true));
        postalAreas.add(new TaiwanPostalArea("358", "苗栗縣", "苑裡鎮", true));
        postalAreas.add(new TaiwanPostalArea("360", "苗栗縣", "苗栗市", true));
        postalAreas.add(new TaiwanPostalArea("361", "苗栗縣", "造橋鄉", true));
        postalAreas.add(new TaiwanPostalArea("362", "苗栗縣", "頭屋鄉", true));
        postalAreas.add(new TaiwanPostalArea("363", "苗栗縣", "公館鄉", true));
        postalAreas.add(new TaiwanPostalArea("364", "苗栗縣", "大湖鄉", true));
        postalAreas.add(new TaiwanPostalArea("365", "苗栗縣", "泰安鄉", true));
        postalAreas.add(new TaiwanPostalArea("366", "苗栗縣", "銅鑼鄉", true));
        postalAreas.add(new TaiwanPostalArea("367", "苗栗縣", "三義鄉", true));
        postalAreas.add(new TaiwanPostalArea("368", "苗栗縣", "西湖鄉", true));
        postalAreas.add(new TaiwanPostalArea("369", "苗栗縣", "卓蘭鎮", true));

        // 彰化縣
        postalAreas.add(new TaiwanPostalArea("500", "彰化縣", "彰化市", true));
        postalAreas.add(new TaiwanPostalArea("502", "彰化縣", "芬園鄉", true));
        postalAreas.add(new TaiwanPostalArea("503", "彰化縣", "花壇鄉", true));
        postalAreas.add(new TaiwanPostalArea("504", "彰化縣", "秀水鄉", true));
        postalAreas.add(new TaiwanPostalArea("505", "彰化縣", "鹿港鎮", true));
        postalAreas.add(new TaiwanPostalArea("506", "彰化縣", "福興鄉", true));
        postalAreas.add(new TaiwanPostalArea("507", "彰化縣", "線西鄉", true));
        postalAreas.add(new TaiwanPostalArea("508", "彰化縣", "和美鎮", true));
        postalAreas.add(new TaiwanPostalArea("509", "彰化縣", "伸港鄉", true));
        postalAreas.add(new TaiwanPostalArea("510", "彰化縣", "員林市", true));
        postalAreas.add(new TaiwanPostalArea("511", "彰化縣", "社頭鄉", true));
        postalAreas.add(new TaiwanPostalArea("512", "彰化縣", "永靖鄉", true));
        postalAreas.add(new TaiwanPostalArea("513", "彰化縣", "埔心鄉", true));
        postalAreas.add(new TaiwanPostalArea("514", "彰化縣", "溪湖鎮", true));
        postalAreas.add(new TaiwanPostalArea("515", "彰化縣", "大村鄉", true));
        postalAreas.add(new TaiwanPostalArea("516", "彰化縣", "埔鹽鄉", true));
        postalAreas.add(new TaiwanPostalArea("520", "彰化縣", "田中鎮", true));
        postalAreas.add(new TaiwanPostalArea("521", "彰化縣", "北斗鎮", true));
        postalAreas.add(new TaiwanPostalArea("522", "彰化縣", "田尾鄉", true));
        postalAreas.add(new TaiwanPostalArea("523", "彰化縣", "埤頭鄉", true));
        postalAreas.add(new TaiwanPostalArea("524", "彰化縣", "溪州鄉", true));
        postalAreas.add(new TaiwanPostalArea("525", "彰化縣", "竹塘鄉", true));
        postalAreas.add(new TaiwanPostalArea("526", "彰化縣", "二林鎮", true));
        postalAreas.add(new TaiwanPostalArea("527", "彰化縣", "大城鄉", true));
        postalAreas.add(new TaiwanPostalArea("528", "彰化縣", "芳苑鄉", true));
        postalAreas.add(new TaiwanPostalArea("530", "彰化縣", "二水鄉", true));

        // 南投縣
        postalAreas.add(new TaiwanPostalArea("540", "南投縣", "南投市", true));
        postalAreas.add(new TaiwanPostalArea("541", "南投縣", "中寮鄉", true));
        postalAreas.add(new TaiwanPostalArea("542", "南投縣", "草屯鎮", true));
        postalAreas.add(new TaiwanPostalArea("544", "南投縣", "國姓鄉", true));
        postalAreas.add(new TaiwanPostalArea("545", "南投縣", "埔里鎮", true));
        postalAreas.add(new TaiwanPostalArea("546", "南投縣", "仁愛鄉", true));
        postalAreas.add(new TaiwanPostalArea("551", "南投縣", "名間鄉", true));
        postalAreas.add(new TaiwanPostalArea("552", "南投縣", "集集鎮", true));
        postalAreas.add(new TaiwanPostalArea("553", "南投縣", "水里鄉", true));
        postalAreas.add(new TaiwanPostalArea("555", "南投縣", "魚池鄉", true));
        postalAreas.add(new TaiwanPostalArea("556", "南投縣", "信義鄉", true));
        postalAreas.add(new TaiwanPostalArea("557", "南投縣", "竹山鎮", true));
        postalAreas.add(new TaiwanPostalArea("558", "南投縣", "鹿谷鄉", true));

        // 雲林縣
        postalAreas.add(new TaiwanPostalArea("640", "雲林縣", "斗六市", true));
        postalAreas.add(new TaiwanPostalArea("643", "雲林縣", "林內鄉", true));
        postalAreas.add(new TaiwanPostalArea("646", "雲林縣", "古坑鄉", true));
        postalAreas.add(new TaiwanPostalArea("647", "雲林縣", "莿桐鄉", true));
        postalAreas.add(new TaiwanPostalArea("648", "雲林縣", "西螺鎮", true));
        postalAreas.add(new TaiwanPostalArea("649", "雲林縣", "二崙鄉", true));
        postalAreas.add(new TaiwanPostalArea("651", "雲林縣", "崙背鄉", true));
        postalAreas.add(new TaiwanPostalArea("652", "雲林縣", "麥寮鄉", true));
        postalAreas.add(new TaiwanPostalArea("653", "雲林縣", "台西鄉", true));
        postalAreas.add(new TaiwanPostalArea("654", "雲林縣", "東勢鄉", true));
        postalAreas.add(new TaiwanPostalArea("655", "雲林縣", "褒忠鄉", true));
        postalAreas.add(new TaiwanPostalArea("630", "雲林縣", "斗南鎮", true));
        postalAreas.add(new TaiwanPostalArea("631", "雲林縣", "大埤鄉", true));
        postalAreas.add(new TaiwanPostalArea("632", "雲林縣", "虎尾鎮", true));
        postalAreas.add(new TaiwanPostalArea("633", "雲林縣", "土庫鎮", true));
        postalAreas.add(new TaiwanPostalArea("634", "雲林縣", "褒忠鄉", true));
        postalAreas.add(new TaiwanPostalArea("635", "雲林縣", "東勢鄉", true));
        postalAreas.add(new TaiwanPostalArea("636", "雲林縣", "台西鄉", true));
        postalAreas.add(new TaiwanPostalArea("637", "雲林縣", "崙背鄉", true));
        postalAreas.add(new TaiwanPostalArea("638", "雲林縣", "麥寮鄉", true));
        postalAreas.add(new TaiwanPostalArea("640", "雲林縣", "斗六市", true));
        postalAreas.add(new TaiwanPostalArea("643", "雲林縣", "林內鄉", true));
        postalAreas.add(new TaiwanPostalArea("646", "雲林縣", "古坑鄉", true));
        postalAreas.add(new TaiwanPostalArea("647", "雲林縣", "莿桐鄉", true));
        postalAreas.add(new TaiwanPostalArea("648", "雲林縣", "西螺鎮", true));
        postalAreas.add(new TaiwanPostalArea("649", "雲林縣", "二崙鄉", true));
        postalAreas.add(new TaiwanPostalArea("651", "雲林縣", "崙背鄉", true));
        postalAreas.add(new TaiwanPostalArea("652", "雲林縣", "麥寮鄉", true));
        postalAreas.add(new TaiwanPostalArea("653", "雲林縣", "台西鄉", true));
        postalAreas.add(new TaiwanPostalArea("654", "雲林縣", "東勢鄉", true));
        postalAreas.add(new TaiwanPostalArea("655", "雲林縣", "褒忠鄉", true));

        // 嘉義縣
        postalAreas.add(new TaiwanPostalArea("602", "嘉義縣", "番路鄉", true));
        postalAreas.add(new TaiwanPostalArea("603", "嘉義縣", "梅山鄉", true));
        postalAreas.add(new TaiwanPostalArea("604", "嘉義縣", "竹崎鄉", true));
        postalAreas.add(new TaiwanPostalArea("606", "嘉義縣", "中埔鄉", true));
        postalAreas.add(new TaiwanPostalArea("607", "嘉義縣", "大埔鄉", true));
        postalAreas.add(new TaiwanPostalArea("608", "嘉義縣", "水上鄉", true));
        postalAreas.add(new TaiwanPostalArea("611", "嘉義縣", "鹿草鄉", true));
        postalAreas.add(new TaiwanPostalArea("612", "嘉義縣", "太保市", true));
        postalAreas.add(new TaiwanPostalArea("613", "嘉義縣", "朴子市", true));
        postalAreas.add(new TaiwanPostalArea("614", "嘉義縣", "東石鄉", true));
        postalAreas.add(new TaiwanPostalArea("615", "嘉義縣", "六腳鄉", true));
        postalAreas.add(new TaiwanPostalArea("616", "嘉義縣", "新港鄉", true));
        postalAreas.add(new TaiwanPostalArea("621", "嘉義縣", "民雄鄉", true));
        postalAreas.add(new TaiwanPostalArea("622", "嘉義縣", "大林鎮", true));
        postalAreas.add(new TaiwanPostalArea("623", "嘉義縣", "溪口鄉", true));
        postalAreas.add(new TaiwanPostalArea("624", "嘉義縣", "義竹鄉", true));
        postalAreas.add(new TaiwanPostalArea("625", "嘉義縣", "布袋鎮", true));

        // 屏東縣
        postalAreas.add(new TaiwanPostalArea("900", "屏東縣", "屏東市", true));
        postalAreas.add(new TaiwanPostalArea("901", "屏東縣", "三地門鄉", true));
        postalAreas.add(new TaiwanPostalArea("902", "屏東縣", "霧台鄉", true));
        postalAreas.add(new TaiwanPostalArea("903", "屏東縣", "瑪家鄉", true));
        postalAreas.add(new TaiwanPostalArea("904", "屏東縣", "九如鄉", true));
        postalAreas.add(new TaiwanPostalArea("905", "屏東縣", "里港鄉", true));
        postalAreas.add(new TaiwanPostalArea("906", "屏東縣", "高樹鄉", true));
        postalAreas.add(new TaiwanPostalArea("907", "屏東縣", "鹽埔鄉", true));
        postalAreas.add(new TaiwanPostalArea("908", "屏東縣", "長治鄉", true));
        postalAreas.add(new TaiwanPostalArea("909", "屏東縣", "麟洛鄉", true));
        postalAreas.add(new TaiwanPostalArea("911", "屏東縣", "竹田鄉", true));
        postalAreas.add(new TaiwanPostalArea("912", "屏東縣", "內埔鄉", true));
        postalAreas.add(new TaiwanPostalArea("913", "屏東縣", "萬丹鄉", true));
        postalAreas.add(new TaiwanPostalArea("920", "屏東縣", "潮州鎮", true));
        postalAreas.add(new TaiwanPostalArea("921", "屏東縣", "泰武鄉", true));
        postalAreas.add(new TaiwanPostalArea("922", "屏東縣", "來義鄉", true));
        postalAreas.add(new TaiwanPostalArea("923", "屏東縣", "萬巒鄉", true));
        postalAreas.add(new TaiwanPostalArea("924", "屏東縣", "崁頂鄉", true));
        postalAreas.add(new TaiwanPostalArea("925", "屏東縣", "新埤鄉", true));
        postalAreas.add(new TaiwanPostalArea("926", "屏東縣", "南州鄉", true));
        postalAreas.add(new TaiwanPostalArea("927", "屏東縣", "林邊鄉", true));
        postalAreas.add(new TaiwanPostalArea("928", "屏東縣", "東港鎮", true));
        postalAreas.add(new TaiwanPostalArea("929", "屏東縣", "琉球鄉", true));
        postalAreas.add(new TaiwanPostalArea("931", "屏東縣", "佳冬鄉", true));
        postalAreas.add(new TaiwanPostalArea("932", "屏東縣", "新園鄉", true));
        postalAreas.add(new TaiwanPostalArea("940", "屏東縣", "枋寮鄉", true));
        postalAreas.add(new TaiwanPostalArea("941", "屏東縣", "枋山鄉", true));
        postalAreas.add(new TaiwanPostalArea("942", "屏東縣", "春日鄉", true));
        postalAreas.add(new TaiwanPostalArea("943", "屏東縣", "獅子鄉", true));
        postalAreas.add(new TaiwanPostalArea("944", "屏東縣", "車城鄉", true));
        postalAreas.add(new TaiwanPostalArea("945", "屏東縣", "牡丹鄉", true));
        postalAreas.add(new TaiwanPostalArea("946", "屏東縣", "恆春鎮", true));
        postalAreas.add(new TaiwanPostalArea("947", "屏東縣", "滿州鄉", true));

        // 花蓮縣
        postalAreas.add(new TaiwanPostalArea("970", "花蓮縣", "花蓮市", true));
        postalAreas.add(new TaiwanPostalArea("971", "花蓮縣", "新城鄉", true));
        postalAreas.add(new TaiwanPostalArea("972", "花蓮縣", "秀林鄉", true));
        postalAreas.add(new TaiwanPostalArea("973", "花蓮縣", "吉安鄉", true));
        postalAreas.add(new TaiwanPostalArea("974", "花蓮縣", "壽豐鄉", true));
        postalAreas.add(new TaiwanPostalArea("975", "花蓮縣", "鳳林鎮", true));
        postalAreas.add(new TaiwanPostalArea("976", "花蓮縣", "光復鄉", true));
        postalAreas.add(new TaiwanPostalArea("977", "花蓮縣", "豐濱鄉", true));
        postalAreas.add(new TaiwanPostalArea("978", "花蓮縣", "瑞穗鄉", true));
        postalAreas.add(new TaiwanPostalArea("979", "花蓮縣", "萬榮鄉", true));
        postalAreas.add(new TaiwanPostalArea("981", "花蓮縣", "玉里鎮", true));
        postalAreas.add(new TaiwanPostalArea("982", "花蓮縣", "卓溪鄉", true));
        postalAreas.add(new TaiwanPostalArea("983", "花蓮縣", "富里鄉", true));

        // 台東縣
        postalAreas.add(new TaiwanPostalArea("950", "台東縣", "台東市", true));
        postalAreas.add(new TaiwanPostalArea("951", "台東縣", "綠島鄉", true));
        postalAreas.add(new TaiwanPostalArea("952", "台東縣", "蘭嶼鄉", true));
        postalAreas.add(new TaiwanPostalArea("953", "台東縣", "延平鄉", true));
        postalAreas.add(new TaiwanPostalArea("954", "台東縣", "卑南鄉", true));
        postalAreas.add(new TaiwanPostalArea("955", "台東縣", "鹿野鄉", true));
        postalAreas.add(new TaiwanPostalArea("956", "台東縣", "關山鎮", true));
        postalAreas.add(new TaiwanPostalArea("957", "台東縣", "海端鄉", true));
        postalAreas.add(new TaiwanPostalArea("958", "台東縣", "池上鄉", true));
        postalAreas.add(new TaiwanPostalArea("959", "台東縣", "東河鄉", true));
        postalAreas.add(new TaiwanPostalArea("961", "台東縣", "成功鎮", true));
        postalAreas.add(new TaiwanPostalArea("962", "台東縣", "長濱鄉", true));
        postalAreas.add(new TaiwanPostalArea("963", "台東縣", "太麻里鄉", true));
        postalAreas.add(new TaiwanPostalArea("964", "台東縣", "金峰鄉", true));
        postalAreas.add(new TaiwanPostalArea("965", "台東縣", "大武鄉", true));
        postalAreas.add(new TaiwanPostalArea("966", "台東縣", "達仁鄉", true));

        // 澎湖縣
        postalAreas.add(new TaiwanPostalArea("880", "澎湖縣", "馬公市", true));
        postalAreas.add(new TaiwanPostalArea("881", "澎湖縣", "西嶼鄉", true));
        postalAreas.add(new TaiwanPostalArea("882", "澎湖縣", "望安鄉", true));
        postalAreas.add(new TaiwanPostalArea("883", "澎湖縣", "七美鄉", true));
        postalAreas.add(new TaiwanPostalArea("884", "澎湖縣", "白沙鄉", true));
        postalAreas.add(new TaiwanPostalArea("885", "澎湖縣", "湖西鄉", true));

        // 金門縣
        postalAreas.add(new TaiwanPostalArea("890", "金門縣", "金沙鎮", true));
        postalAreas.add(new TaiwanPostalArea("891", "金門縣", "金湖鎮", true));
        postalAreas.add(new TaiwanPostalArea("892", "金門縣", "金寧鄉", true));
        postalAreas.add(new TaiwanPostalArea("893", "金門縣", "金城鎮", true));
        postalAreas.add(new TaiwanPostalArea("894", "金門縣", "烈嶼鄉", true));
        postalAreas.add(new TaiwanPostalArea("896", "金門縣", "烏坵鄉", true));

        // 連江縣
        postalAreas.add(new TaiwanPostalArea("209", "連江縣", "南竿鄉", true));
        postalAreas.add(new TaiwanPostalArea("210", "連江縣", "北竿鄉", true));
        postalAreas.add(new TaiwanPostalArea("211", "連江縣", "莒光鄉", true));
        postalAreas.add(new TaiwanPostalArea("212", "連江縣", "東引鄉", true));

        cityList = new ArrayList<>();
        cityList.add("台北市");
        cityList.add("新北市");
        cityList.add("桃園市");
        cityList.add("台中市");
        cityList.add("台南市");
        cityList.add("高雄市");
        cityList.add("基隆市");
        cityList.add("新竹市");
        cityList.add("嘉義市");
        cityList.add("宜蘭縣");
        cityList.add("新竹縣");
        cityList.add("苗栗縣");
        cityList.add("彰化縣");
        cityList.add("南投縣");
        cityList.add("雲林縣");
        cityList.add("嘉義縣");
        cityList.add("屏東縣");
        cityList.add("宜蘭縣");
        cityList.add("花蓮縣");
        cityList.add("台東縣");
        cityList.add("澎湖縣");
        cityList.add("金門縣");
        cityList.add("連江縣");

        // 建立索引
        buildIndexes();
    }

    private void buildIndexes() {
        // 郵遞區號索引 - 使用 List 存儲重複的郵遞區號
        postalCodeMap = postalAreas.stream()
                .collect(Collectors.groupingBy(TaiwanPostalArea::getPostalCode));

        // 城市索引
        cityMap = postalAreas.stream()
                .collect(Collectors.groupingBy(TaiwanPostalArea::getCity));

        // 行政區索引
        districtMap = postalAreas.stream()
                .collect(Collectors.groupingBy(TaiwanPostalArea::getDistrict));
    }

    /**
     * 根據郵遞區號查詢
     */
    public List<TaiwanPostalArea> findByPostalCode(String postalCode) {
        return postalCodeMap.getOrDefault(postalCode, Collections.emptyList());
    }

    /**
     * 根據城市查詢
     */
    public List<TaiwanPostalArea> findByCity(String city) {
        return cityMap.getOrDefault(city, Collections.emptyList());
    }

    /**
     * 根據行政區查詢
     */
    public List<TaiwanPostalArea> findByDistrict(String district) {
        return districtMap.getOrDefault(district, Collections.emptyList());
    }

    /**
     * 根據城市和行政區查詢
     */
    public List<TaiwanPostalArea> findByCityAndDistrict(String city, String district) {
        return postalAreas.stream()
                .filter(area -> area.getCity().equals(city) && area.getDistrict().equals(district))
                .collect(Collectors.toList());
    }

    /**
     * 獲取所有啟用的郵遞區號
     */
    public List<TaiwanPostalArea> findAllActive() {
        return postalAreas.stream()
                .filter(TaiwanPostalArea::isActive)
                .collect(Collectors.toList());
    }

    /**
     * 獲取城市列表
     */
    public List<String> getCityList() {
        return cityList;
    }

    /**
     * 獲取指定城市的所有行政區
     */
    public List<String> getDistrictsByCity(String city) {
        return findByCity(city).stream()
                .map(TaiwanPostalArea::getDistrict)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 驗證郵遞區號是否有效
     */
    public boolean isValidPostalCode(String postalCode) {
        List<TaiwanPostalArea> areas = postalCodeMap.get(postalCode);
        return areas != null && !areas.isEmpty() && areas.get(0).isActive();
    }

    /**
     * 搜索郵遞區號（支持模糊搜索）
     */
    public List<TaiwanPostalArea> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String lowerKeyword = keyword.toLowerCase();
        return postalAreas.stream()
                .filter(area ->
                        area.getPostalCode().contains(lowerKeyword) ||
                                area.getCity().toLowerCase().contains(lowerKeyword) ||
                                area.getDistrict().toLowerCase().contains(lowerKeyword))
                .collect(Collectors.toList());
    }
} 