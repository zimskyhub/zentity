package com.zmtech.zkit.message.impl;

import com.zmtech.zkit.message.MessageFacade;
import com.zmtech.zkit.message.NotificationMessage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;


public class MessageFacadeImpl implements MessageFacade {
    protected final static Logger logger = LoggerFactory.getLogger(MessageFacadeImpl.class);

    private final static List<String> emptyStringList = Collections.unmodifiableList(new ArrayList<>());
    private final static List<ValidationError> emptyValidationErrorList = Collections.unmodifiableList(new ArrayList<>());
    private final static List<MessageInfo> emptyMessageInfoList = Collections.unmodifiableList(new ArrayList<>());

    private ArrayList<MessageInfo> messageList = null;
    private ArrayList<String> errorList = null;
    private ArrayList<ValidationError> validationErrorList = null;
    private ArrayList<MessageInfo> publicMessageList = null;
    private boolean hasErrors = false;

    private LinkedList<SavedErrors> savedErrorsStack = null;

    public MessageFacadeImpl() { }

    @Override
    public List<String> getMessages() {
        if (messageList == null) return emptyStringList;
        ArrayList<String> strList = new ArrayList<>(messageList.size());
        for (int i = 0; i < messageList.size(); i++) strList.add((messageList.get(i)).getMessage());
        return strList;
    }
    @Override
    public List<MessageInfo> getMessageInfos() {
        if (messageList == null) return emptyMessageInfoList;
        return Collections.unmodifiableList(messageList);
    }
    @Override
    public String getMessagesString() {
        if (messageList == null) return "";
        StringBuilder messageBuilder = new StringBuilder();
        for (MessageInfo message : messageList) messageBuilder.append(message.getMessage()).append("\n");
        return messageBuilder.toString();
    }
    @Override
    public void addMessage(String message) { addMessage(message, info); }
    @Override
    public void addMessage(String message, NotificationType type) { addMessage(message, type != null?type.toString():null); }
    @Override
    public void addMessage(String message, String type) {
        if (message == null || message.isEmpty()) return;
        if (messageList == null) messageList = new ArrayList<>();
        MessageInfo mi = new MessageInfo(message, type);
        messageList.add(mi);
        logger.info(mi.toString());
    }

    @Override
    public void addPublic(String message, NotificationType type) { addPublic(message, type != null?type.toString():null); }
    @Override
    public void addPublic(String message, String type) {
        if (message == null || message.isEmpty()) return;
        if (publicMessageList == null) publicMessageList = new ArrayList<>();
        if (messageList == null) messageList = new ArrayList<>();
        MessageInfo mi = new MessageInfo(message, type);
        publicMessageList.add(mi);
        messageList.add(mi);
        logger.info(mi.toString());
    }

    @Override
    public List<String> getPublicMessages() {
        if (publicMessageList == null) return emptyStringList;
        ArrayList<String> strList = new ArrayList<>(publicMessageList.size());
        for (int i = 0; i < publicMessageList.size(); i++) strList.add((publicMessageList.get(i)).getMessage());
        return strList;
    }
    @Override
    public List<MessageInfo> getPublicMessageInfos() {
        if (publicMessageList == null) return emptyMessageInfoList;
        return Collections.unmodifiableList(publicMessageList);
    }

    @Override
    public List<String> getErrors() {
        if (errorList == null) return emptyStringList;
        return Collections.unmodifiableList(errorList);
    }
    @Override
    public void addError(String error) {
        if (error == null || error.isEmpty()) return;
        if (errorList == null) errorList = new ArrayList<>();
        errorList.add(error);
        logger.error(error);
        hasErrors = true;
    }

    @Override
    public List<ValidationError> getValidationErrors() {
        if (validationErrorList == null) return emptyValidationErrorList;
        return Collections.unmodifiableList(validationErrorList);
    }
    @Override
    public void addValidationError(String form, String field, String serviceName, String message, Throwable nested) {
        if (message == null || message.isEmpty()) return;
        if (validationErrorList == null) validationErrorList = new ArrayList<>();
        ValidationError ve = new ValidationError(form, field, serviceName, message, nested);
        validationErrorList.add(ve);
        logger.error(ve.getMap().toString());
        hasErrors = true;
    }
    @Override
    public void addError(ValidationError error) {
        if (error == null) return;
        if (validationErrorList == null) validationErrorList = new ArrayList<>();
        validationErrorList.add(error);
        logger.error(error.getMap().toString());
        hasErrors = true;
    }

    @Override
    public boolean hasError() { return hasErrors; }
    @Override
    public String getErrorsString() {
        StringBuilder errorBuilder = new StringBuilder();
        if (errorList != null) for (String errorMessage : errorList) errorBuilder.append(errorMessage).append("\n");
        if (validationErrorList != null) for (ValidationError validationError : validationErrorList) {
            errorBuilder.append(validationError.toStringPretty()).append("\n");
        }
        return errorBuilder.toString();
    }

    @Override
    public void clearAll() {
        if (messageList != null) messageList.clear();
        if (publicMessageList != null) publicMessageList.clear();
        clearErrors();
    }
    @Override
    public void clearErrors() {
        if (errorList != null) errorList.clear();
        if (validationErrorList != null) validationErrorList.clear();
        hasErrors = false;
    }

    private void moveErrorsToDangerMessages() {
        if (errorList != null) {
            for (String errMsg : errorList) addMessage(errMsg, danger);
            errorList.clear();
        }
        if (validationErrorList != null) {
            for (ValidationError ve : validationErrorList) addMessage(ve.toStringPretty(), danger);
            validationErrorList.clear();
        }
        hasErrors = false;
    }

    @Override
    public void copyMessages(MessageFacade mf) {
        if (mf.getMessageInfos() != null && !mf.getMessageInfos().isEmpty()) {
            if (messageList == null) messageList = new ArrayList<>();
            messageList.addAll(mf.getMessageInfos());
        }
        if (mf.getErrors() != null && !mf.getErrors().isEmpty()) {
            if (errorList == null) errorList = new ArrayList<>();
            errorList.addAll(mf.getErrors());
            hasErrors = true;
        }
        if (mf.getValidationErrors() != null && !mf.getValidationErrors().isEmpty()) {
            if (validationErrorList == null) validationErrorList = new ArrayList<>();
            validationErrorList.addAll(mf.getValidationErrors());
            hasErrors = true;
        }
        if (mf.getPublicMessageInfos() != null && !mf.getPublicMessageInfos().isEmpty()) {
            if (publicMessageList == null) publicMessageList = new ArrayList<>();
            publicMessageList.addAll(mf.getPublicMessageInfos());
        }
    }

    @Override
    public void pushErrors() {
        if (savedErrorsStack == null) savedErrorsStack = new LinkedList<>();
        savedErrorsStack.addFirst(new SavedErrors(errorList, validationErrorList));
        errorList = null;
        validationErrorList = null;
        hasErrors = false;
    }
    @Override
    public void popErrors() {
        if (savedErrorsStack == null || savedErrorsStack.size() == 0) return;
        SavedErrors se = savedErrorsStack.removeFirst();
        if (se.errorList != null && se.errorList.size() > 0) {
            if (errorList == null) errorList = new ArrayList<>();
            errorList.addAll(se.errorList);
            hasErrors = true;
        }
        if (se.validationErrorList != null && se.validationErrorList.size() > 0) {
            if (validationErrorList == null) validationErrorList = new ArrayList<>();
            validationErrorList.addAll(se.validationErrorList);
            hasErrors = true;
        }
    }

    public static class SavedErrors {
        public List<String> errorList;
        public List<ValidationError> validationErrorList;
        public SavedErrors(List<String> errorList, List<ValidationError> validationErrorList) {
            this.errorList = errorList;
            this.validationErrorList = validationErrorList;
        }
    }
}
