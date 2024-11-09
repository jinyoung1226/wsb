package com.ebiz.wsb.domain.notification.application;

import com.ebiz.wsb.domain.alert.application.AlertService;
import com.ebiz.wsb.domain.alert.entity.Alert;
import com.ebiz.wsb.domain.auth.application.UserDetailsServiceImpl;
import com.ebiz.wsb.domain.guardian.entity.Guardian;
import com.ebiz.wsb.domain.guardian.repository.GuardianRepository;
import com.ebiz.wsb.domain.notification.dto.AndroidNotificationDTO;
import com.ebiz.wsb.domain.notification.dto.FcmMessage;
import com.ebiz.wsb.domain.notification.dto.FcmMessage.Notification;
import com.ebiz.wsb.domain.notification.dto.NotificationDTO;
import com.ebiz.wsb.domain.notification.dto.PushType;
import com.ebiz.wsb.domain.notification.entity.FcmToken;
import com.ebiz.wsb.domain.notification.entity.UserType;
import com.ebiz.wsb.domain.notification.exception.PushNotFoundException;
import com.ebiz.wsb.domain.notification.repository.FcmTokenRepository;
import com.ebiz.wsb.domain.parent.entity.Parent;
import com.ebiz.wsb.domain.parent.repository.ParentRepository;
import com.ebiz.wsb.domain.student.entity.Student;
import com.ebiz.wsb.domain.student.repository.StudentRepository;
import com.ebiz.wsb.domain.waypoint.entity.Waypoint;
import com.ebiz.wsb.domain.waypoint.repository.WaypointRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.net.HttpHeaders;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.TopicManagementResponse;
import java.io.FileInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import okhttp3.RequestBody;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private final UserDetailsServiceImpl userDetailsService;
    private final FcmTokenRepository fcmTokenRepository;
    private final ObjectMapper objectMapper;
    private final AlertService alertService;
    private final ParentRepository parentRepository;
    private final GuardianRepository guardianRepository;
    private final WaypointRepository waypointRepository;
    private final StudentRepository studentRepository;

    @Value("${fcm.project.id}")
    private String PROJECT_ID;
    private static final String FCM_SEND_URL = "https://fcm.googleapis.com/v1/projects/%s/messages:send";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    public void sendPushMessage(String title, String body, Map<String, String> data, String token)
            throws IOException {
        String message = makeMessage(title, body, data, token);
        OkHttpClient client = new OkHttpClient();
        RequestBody requestBody = RequestBody.create(JSON_MEDIA_TYPE, message);
        Request request = new Request.Builder()
                .addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken())
                .addHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8")
                .post(requestBody)
                .url(String.format(FCM_SEND_URL, PROJECT_ID))
                .build();
        Response response = client.newCall(request).execute();
        response.close();
    }


    private String makeMessage(String title, String body, Map<String, String> data, String token)
            throws JsonProcessingException {
        AndroidNotificationDTO android = new AndroidNotificationDTO();
        NotificationDTO notificationDTO = new NotificationDTO();
        notificationDTO.setTitle(title);
        notificationDTO.setBody(body);
        android.setNotification(notificationDTO);

        FcmMessage fcmMessage = FcmMessage.builder()
                .message(FcmMessage.Message.builder()
                        .token(token)
                        .data(data)
                        .android(android)
                        .notification(Notification.builder()
                                .title(title)
                                .body(body)
                                .build())
                        .build())
                .build();

        return objectMapper.writeValueAsString(fcmMessage);
    }

    private String getAccessToken() throws IOException {

        String firebaseConfigPath = System.getenv("FIREBASE_CONFIG_PATH");

        if (firebaseConfigPath == null) {
            throw new IOException("Firebase config path environment variable not set");
        }

        FileInputStream serviceAccount = new FileInputStream(firebaseConfigPath);

        GoogleCredentials googleCredentials = GoogleCredentials.fromStream(serviceAccount)
                .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
        googleCredentials.refreshIfExpired();
        return googleCredentials.getAccessToken().getTokenValue();
    }


    public void save(String token) {
        Object user = userDetailsService.getUserByContextHolder();

        Long userId;
        if (user instanceof Guardian) {
            userId = ((Guardian) user).getId();
        } else if (user instanceof Parent) {
            userId = ((Parent) user).getId();
        } else {
            throw new IllegalArgumentException("알 수 없는 사용자 타입입니다.");
        }

        FcmToken fcmToken = FcmToken.builder()
                .userId(userId)
                .token(token)
                .build();
        fcmTokenRepository.save(fcmToken);
    }

    // 관리자 공지사항과 스케쥴 등록 때 사용
    public void sendPushNotificationToGroup(Long groupId, String title, String body, PushType pushType) {

        List<Parent> parents = parentRepository.findByGroupId(groupId);
        List<Guardian> guardians = guardianRepository.findByGroupId(groupId);

        List<String> fcmTokens = new ArrayList<>();

        for (Parent parent : parents) {
            List<FcmToken> parentTokens = fcmTokenRepository.findByUserIdAndUserType(parent.getId(), UserType.PARENT);
            parentTokens.forEach(token -> fcmTokens.add(token.getToken()));
        }

        for (Guardian guardian : guardians) {
            List<FcmToken> guardianTokens = fcmTokenRepository.findByUserIdAndUserType(guardian.getId(), UserType.GUARDIAN);
            guardianTokens.forEach(token -> fcmTokens.add(token.getToken()));
        }

        Map<String, String> data = createPushData(pushType);
        Alert.AlertCategory alertCategory = mapPushTypeToAlertCategory(pushType);

        for (String token : fcmTokens) {
            Long userId = fcmTokenRepository.findByToken(token)
                    .map(FcmToken::getUserId)
                    .orElse(null);

            if (userId != null) {
                try {
                    alertService.createAlert(userId, alertCategory, title, body);

                    sendPushMessage(title, body, data, token);

                } catch (IOException e) {
                    log.error("푸시 메시지 전송 실패: token={} / error: {}", token, e.getMessage());
                } catch (Exception e) {
                    log.error("Alert 저장 실패 또는 예외 발생: userId={}, error: {}", userId, e.getMessage());
                }
            } else {
                log.warn("유효하지 않은 토큰으로 알림 전송 시도: token={}", token);
            }
        }
    }

    // 출근하기,퇴근하기는 그룹 안의 인솔자와 학부모에게 서로 다른 메시지 title,body가 가기 때문에 로직을 나눔
    public void sendPushNotificationToGroupDifferentMessage(Long groupId, String parentTitle, String parentBody, String guardianTitle, String guardianBody, PushType parentPushType, PushType guardianPushType) {

        List<Parent> parents = parentRepository.findByGroupId(groupId);
        List<Guardian> guardians = guardianRepository.findByGroupId(groupId);

        // 부모와 인솔자 각각의 FCM 토큰 리스트
        List<String> parentTokens = new ArrayList<>();
        List<String> guardianTokens = new ArrayList<>();

        for (Parent parent : parents) {
            List<FcmToken> parentTokensList = fcmTokenRepository.findByUserIdAndUserType(parent.getId(), UserType.PARENT);
            parentTokensList.forEach(token -> parentTokens.add(token.getToken()));
        }

        for (Guardian guardian : guardians) {
            List<FcmToken> guardianTokensList = fcmTokenRepository.findByUserIdAndUserType(guardian.getId(), UserType.GUARDIAN);
            guardianTokensList.forEach(token -> guardianTokens.add(token.getToken()));
        }

        Map<String, String> parentData = createPushData(parentPushType);
        Map<String, String> guardianData = createPushData(guardianPushType);

        Alert.AlertCategory parentAlertCategory = mapPushTypeToAlertCategory(parentPushType);
        Alert.AlertCategory guardianAlertCategory = mapPushTypeToAlertCategory(guardianPushType);

        // 부모에게 메시지 전송
        for (String token : parentTokens) {
            Long userId = fcmTokenRepository.findByToken(token)
                    .map(FcmToken::getUserId)
                    .orElse(null);

            if (userId != null) {
                try {
                    alertService.createAlert(userId, parentAlertCategory, parentTitle, parentBody);
                    sendPushMessage(parentTitle, parentBody, parentData, token);
                } catch (IOException e) {
                    log.error("부모에게 푸시 메시지 전송 실패: token={} / error: {}", token, e.getMessage());
                } catch (Exception e) {
                    log.error("부모 Alert 저장 실패 또는 예외 발생: userId={}, error: {}", userId, e.getMessage());
                }
            } else {
                log.warn("유효하지 않은 부모 토큰으로 알림 전송 시도: token={}", token);
            }
        }

        // 인솔자에게 메시지 전송
        for (String token : guardianTokens) {
            Long userId = fcmTokenRepository.findByToken(token)
                    .map(FcmToken::getUserId)
                    .orElse(null);

            if (userId != null) {
                try {
                    alertService.createAlert(userId, guardianAlertCategory, guardianTitle, guardianBody);
                    sendPushMessage(guardianTitle, guardianBody, guardianData, token);
                } catch (IOException e) {
                    log.error("인솔자에게 푸시 메시지 전송 실패: token={} / error: {}", token, e.getMessage());
                } catch (Exception e) {
                    log.error("인솔자 Alert 저장 실패 또는 예외 발생: userId={}, error: {}", userId, e.getMessage());
                }
            } else {
                log.warn("유효하지 않은 인솔자 토큰으로 알림 전송 시도: token={}", token);
            }
        }
    }

    public void sendPushNotifcationToGuardians(Long groupId, String title, String body, PushType pushType){
        List<Guardian> guardians = guardianRepository.findByGroupId(groupId);

        List<String> guardianTokens = new ArrayList<>();
        for(Guardian guardian : guardians){
            List<FcmToken> tokens = fcmTokenRepository.findByUserIdAndUserType(guardian.getId(), UserType.GUARDIAN);
            tokens.forEach(token -> guardianTokens.add(token.getToken()));
        }

        Map<String, String> data = createPushData(pushType);
        Alert.AlertCategory alertCategory = mapPushTypeToAlertCategory(pushType);

        for ( String token : guardianTokens){
            Long userId = fcmTokenRepository.findByToken(token)
                    .map(FcmToken::getUserId)
                    .orElse(null);

            if(userId != null){
                try{
                    alertService.createAlert(userId, alertCategory, title, body);
                    sendPushMessage(title, body, data, token);
                } catch (IOException e){
                    log.error("푸시 메시지 전송 실패: token={} / error : {}", token, e.getMessage());
                } catch (Exception e) {
                    log.error("Alert 저장 실패 또는 예외 발생: userId={}, error: {}", userId, e.getMessage());
                }
            } else {
                log.warn("유효하지 않은 토큰으로 알림 전송 시도: token={}", token);
            }
        }
    }

    public void sendPushNotificationToParents(Long groupId, String title, String body, PushType pushType) {
        List<Parent> parents = parentRepository.findByGroupId(groupId);

        List<String> parentTokens = new ArrayList<>();

        for (Parent parent : parents) {
            List<FcmToken> tokens = fcmTokenRepository.findByUserIdAndUserType(parent.getId(), UserType.PARENT);
            tokens.forEach(token -> parentTokens.add(token.getToken()));
        }

        Map<String, String> data = createPushData(pushType);
        Alert.AlertCategory alertCategory = mapPushTypeToAlertCategory(pushType);

        for (String token : parentTokens) {
            Long userId = fcmTokenRepository.findByToken(token)
                    .map(FcmToken::getUserId)
                    .orElse(null);

            if (userId != null) {
                try {
                    alertService.createAlert(userId, alertCategory, title, body);
                    sendPushMessage(title, body, data, token);
                } catch (IOException e) {
                    log.error("푸시 메시지 전송 실패: token={} / error: {}", token, e.getMessage());
                } catch (Exception e) {
                    log.error("Alert 저장 실패 또는 예외 발생: userId={}, error: {}", userId, e.getMessage());
                }
            } else {
                log.warn("유효하지 않은 토큰으로 알림 전송 시도: token={}", token);
            }
        }
    }

    public void sendPushNotificationToParentsAtWaypoint(Long waypointId, String title, String body, PushType pushType) {
        List<Student> students = studentRepository.findByWaypointId(waypointId);
        List<Long> parentIds = new ArrayList<>();

        for (Student student : students) {
            Long parentId = student.getParent() != null ? student.getParent().getId() : null;
            if (!parentIds.contains(parentId)) {
                parentIds.add(parentId);
            }
        }

        List<String> parentTokens = new ArrayList<>();

        for (Long parentId : parentIds) {
            List<FcmToken> tokens = fcmTokenRepository.findByUserIdAndUserType(parentId, UserType.PARENT);
            log.info("Parent ID {} has {} tokens", parentId, tokens.size());
            tokens.forEach(token -> parentTokens.add(token.getToken()));
        }

        Map<String, String> data = createPushData(pushType);
        Alert.AlertCategory alertCategory = mapPushTypeToAlertCategory(pushType);

        log.info(data.toString());

        for (String token : parentTokens) {
            Long userId = fcmTokenRepository.findByToken(token)
                    .map(FcmToken::getUserId)
                    .orElse(null);

            if (userId != null) {
                try {
                    alertService.createAlert(userId, alertCategory, title, body);
                    sendPushMessage(title, body, data, token);
                } catch (IOException e) {
                    log.error("푸시 메시지 전송 실패: token={} / error: {}", token, e.getMessage());
                } catch (Exception e) {
                    log.error("Alert 저장 실패 또는 예외 발생: userId={}, error: {}", userId, e.getMessage());
                }
            } else {
                log.warn("유효하지 않은 토큰으로 알림 전송 시도: token={}", token);
            }
        }
    }


    public Map<String, String> createPushData(PushType pushType) {
        Map<String, String> data = new HashMap<>();

        switch (pushType) {
            case POST:
                data.put("title", "새로운 공지사항이 등록되었어요!");
                data.put("body", "%s 지도사님이 새로운 공지사항을 작성했어요.");
                break;
            case APP:
                data.put("title", "시스템 공지");
                data.put("body", "시스템 공지입니다.");
                break;
            case SCHEDULE:
                data.put("title", "새로운 스케줄");
                data.put("body", "새로운 스케줄이 등록되었어요. 지금 확인해보세요.");
                break;
            case MESSAGE:
                data.put("title", "%s 학생 학부모로부터 메시지가 도착했어요!");
                data.put("body", "%s");
                break;
            case PREABSENT_MESSAGE:
                data.put("title", "%s 학생이 %d월 %d일 결석해요!");
                data.put("body", "%s 학생(%s)이 %d월 %d일 %요일 결석을 신청했어요");
                break;
            case START_WORK_PARENT:
                data.put("title", "워킹스쿨버스가 출발했어요!");
                data.put("body", "워킹스쿨버스가 출발했어요. 늦지 않게 아이를 준비 시켜주세요.");
                break;
            case START_WORK_GUARDIAN:
                data.put("title", "지도사 출근 알림");
                data.put("body", "%s 지도사님이 출근하셨어요.");
                break;
            case PICKUP:
                data.put("title", "자녀의 출석이 확인되었어요!");
                data.put("body", "%d시 %d분에 자녀의 출석이 확인되었어요.");
                break;
            case END_WORK_PARENT:
                data.put("title", "자녀가 학교에 도착했어요!");
                data.put("body", "%d시 %d분에 자녀가 %s에 도착했어요.");
                break;
            case END_WORK_GUARDIAN:
                data.put("title", "지도사 퇴근 알림");
                data.put("body", "%s 지도사님이 출근하셨어요.");
                break;
            default:
                data.put("title", "일반 공지");
                data.put("body", "일반 공지사항입니다.");
        }

        return data;
    }

    private Alert.AlertCategory mapPushTypeToAlertCategory(PushType pushType) {
        switch (pushType) {
            case POST:
                return Alert.AlertCategory.POST;
            case APP:
                return Alert.AlertCategory.APP;
            case SCHEDULE:
                return Alert.AlertCategory.SCHEDULE;
            case MESSAGE:
                return Alert.AlertCategory.MESSAGE;
            case PREABSENT_MESSAGE:
                return Alert.AlertCategory.PREABSENT_MESSAGE;
            case START_WORK_PARENT:
                return Alert.AlertCategory.START_WORK_PARENT;
            case START_WORK_GUARDIAN:
                return Alert.AlertCategory.START_WORK_GUARDIAN;
            case PICKUP:
                return Alert.AlertCategory.PICKUP;
            case END_WORK_PARENT:
                return Alert.AlertCategory.END_WORK_PARENT;
            case END_WORK_GUARDIAN:
                return Alert.AlertCategory.END_WORK_GUARDIAN;
            default:
                throw new PushNotFoundException("지원되지 않는 PushType입니다.");
        }
    }


    // 테스트용 메서드
    public void sendMessageToToken(Long userId, String title, String body, Map<String, String> data, String token, Alert.AlertCategory category) throws IOException {
        sendPushMessage( title, body, data, token);
    }
}
