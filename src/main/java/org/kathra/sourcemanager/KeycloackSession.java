/* 
 * Copyright 2019 The Kathra Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *
 *    IRT SystemX (https://www.kathra.org/)    
 *
 */
package org.kathra.sourcemanager;

import org.kathra.core.model.User;
import org.kathra.utils.KathraSessionManager;
import org.kathra.utils.Session;
import org.kathra.utils.security.KeycloakUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KeycloackSession extends Session implements KathraSessionManager {
    Logger log = LoggerFactory.getLogger("KeycloackSession");
    private SessionToken<String> token = new SessionToken<String>();

    class SessionToken<Type> {
        public Type token;
        public void setToken(Type currentToken) {
            token = currentToken;
        }
        public Type token() {
            return token;
        }
    }

    public KeycloackSession(User user) {
        log.debug("Creating session for" + user.getName());
        this.callerName(user.getName());
        this.setUserObject(user);
        this.performLogin();
        log.debug("Session created for" + user.getName());
    }

    // TODO: remove *AccessToken from org.kathra.utils.Session
    private void performLogin() {
        User sessionUser = getUserObject();
        log.debug("Performing logging for " + sessionUser.getName());
        String loggin_token = KeycloakUtils.login(sessionUser.getName(), sessionUser.getPassword());
        log.debug("Token received: " + loggin_token);
        token.setToken(loggin_token);
        this.setAccessToken(loggin_token);
        authenticated(true);
        log.debug("Session confirmed");
    }

    @Override
    public Session getCurrentSession() {
        return this;
    }

    @Override
    public void handleSession(Session session) {
        // Avoid default behavior with Thread
    }
}
