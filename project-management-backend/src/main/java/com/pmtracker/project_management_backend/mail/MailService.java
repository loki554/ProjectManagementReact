package com.pmtracker.project_management_backend.mail;

import java.util.UUID;

public interface MailService {

    void sendVerificationEmail(String toEmail, UUID token);
}
