package org.powertac.visualizer.user;

import java.io.Serializable;

public class UserSessionBean implements Serializable {

	private static final long serialVersionUID = 1L;

	private String nickname;
	private String message;

	public String getNickname() {
		return nickname;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

}
