package greencity.security.service;

import greencity.entity.RestorePasswordEmail;
import greencity.entity.User;
import greencity.enums.UserStatus;
import greencity.exception.exceptions.BadVerifyEmailTokenException;
import greencity.exception.exceptions.NotFoundException;
import greencity.exception.exceptions.UserActivationEmailTokenExpiredException;
import greencity.exception.exceptions.WrongEmailException;
import greencity.message.PasswordRecoveryMessage;
import greencity.repository.UserRepo;
import greencity.security.events.UpdatePasswordEvent;
import greencity.security.jwt.JwtTool;
import greencity.security.repository.RestorePasswordEmailRepo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordRecoveryServiceImplTest {
    @Mock
    private JwtTool jwtTool;
    @Mock
    private RestorePasswordEmailRepo restorePasswordEmailRepo;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private UserRepo userRepo;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @InjectMocks
    private PasswordRecoveryServiceImpl passwordRecoveryService;
    @Value("${messaging.rabbit.email.topic}")
    private String sendEmailTopic;
    private static final String PASSWORD_RECOVERY_ROUTING_KEY = "password.recovery";

    @Test
    void sendPasswordRecoveryEmailToNonExistentUserTest() {
        String email = "foo";
        String language = "en";
        when(userRepo.findByEmail(email)).thenReturn(Optional.empty());
        Assertions
            .assertThrows(NotFoundException.class,
                () -> passwordRecoveryService.sendPasswordRecoveryEmailTo(email, language));
    }

    @Test
    void sendPasswordRecoveryEmailToUserWithExistentRestorePasswordEmailTest() {
        String email = "foo";
        String language = "en";
        when(userRepo.findByEmail(email)).thenReturn(Optional.of(
            User.builder().restorePasswordEmail(new RestorePasswordEmail()).build()));
        Assertions
            .assertThrows(WrongEmailException.class,
                () -> passwordRecoveryService.sendPasswordRecoveryEmailTo(email, language));
    }

    @Test
    void sendPasswordRecoveryEmailToSimpleTest() {
        String email = "foo";
        String language = "en";
        User user = new User();
        when(userRepo.findByEmail(email)).thenReturn(Optional.of(user));
        String token = "bar";
        when(jwtTool.generateTokenKey()).thenReturn(token);
        ReflectionTestUtils.setField(passwordRecoveryService, "tokenExpirationTimeInHours", 24);
        passwordRecoveryService.sendPasswordRecoveryEmailTo(email, language);
        verify(restorePasswordEmailRepo).save(refEq(
            RestorePasswordEmail.builder()
                .user(user)
                .token(token)
                .build(),
            "expiryDate"));
        verify(rabbitTemplate).convertAndSend(
            refEq(sendEmailTopic),
            refEq(PASSWORD_RECOVERY_ROUTING_KEY),
            refEq(new PasswordRecoveryMessage(
                user.getId(),
                user.getName(),
                user.getEmail(),
                token, language)));
    }

    @Test
    void updatePasswordUsingTokenWithNonExistentTokenTest() {
        String token = "foo";
        String newPassword = "bar";
        when(restorePasswordEmailRepo.findByToken(token)).thenReturn(Optional.empty());
        Assertions
            .assertThrows(BadVerifyEmailTokenException.class,
                () -> passwordRecoveryService.updatePasswordUsingToken(token, newPassword));
    }

    @Test
    void updatePasswordUsingTokenWithExpiredTokenTest() {
        String token = "foo";
        String newPassword = "bar";
        when(restorePasswordEmailRepo.findByToken(token)).thenReturn(Optional.of(
            RestorePasswordEmail.builder()
                .expiryDate(LocalDateTime.now().minusHours(1))
                .user(User.builder().email("foo@bar.com").build())
                .build()));
        Assertions
            .assertThrows(UserActivationEmailTokenExpiredException.class,
                () -> passwordRecoveryService.updatePasswordUsingToken(token, newPassword));
    }

    @Test
    void updatePasswordUsingTokenSimpleTest() {
        String token = "foo";
        String newPassword = "bar";
        User user = User.builder().id(1L).userStatus(UserStatus.CREATED).email("foo@bar.com").build();
        when(restorePasswordEmailRepo.findByToken(token)).thenReturn(Optional.of(
            RestorePasswordEmail.builder()
                .expiryDate(LocalDateTime.now().plusHours(1))
                .user(user)
                .token(token)
                .build()));
        passwordRecoveryService.updatePasswordUsingToken(token, newPassword);
        verify(restorePasswordEmailRepo).delete(refEq(
            RestorePasswordEmail.builder()
                .user(user)
                .token(token)
                .build(),
            "expiryDate"));
        verify(applicationEventPublisher).publishEvent(refEq(
            new UpdatePasswordEvent(passwordRecoveryService.getClass(), newPassword, user.getId()), "timestamp"));
    }

    @Test
    void deleteAllExpiredPasswordResetTokensTest() {
        passwordRecoveryService.deleteAllExpiredPasswordResetTokens();
        verify(restorePasswordEmailRepo).deleteAllExpiredPasswordResetTokens();
    }
}
