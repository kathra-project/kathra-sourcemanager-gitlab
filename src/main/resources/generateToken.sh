#!/bin/bash

function getHttpHeaderLocation() {
    grep -i "< Location" < $1 | sed 's/< Location: //gi' | tr -d '\r\n'
}
export -f getHttpHeaderLocation
function getHttpHeaderSetCookie() {
    grep -i "< Set-Cookie" < $1 | grep -i "$2" | sed 's/< Set-Cookie://gi' | sed 's/;.*//g' | tr '\r\n' ';'
}
export -f getHttpHeaderSetCookie

###
### Generate API Token for GitLab having all authorization
###
function gitlabGenerateApiToken() {
    local gitlab_host=$1
    local keycloak_host=$2
    local login=$3
    local password=$4
    local fileOut=$5
    echo "$*"
    echo "gitlabGenerateApiToken(gitlab_host: $gitlab_host, keycloak_host:$keycloak_host login: $login, password: $password, fileOut: $fileOut)"
    local attempt_counter=0
    local max_attempts=1

    local tmp="/tmp/gitlabGenerateApiToken.$(date +%s%N)"
    mkdir $tmp
    echo "tmp=$tmp"

    while true; do

        curl -v https://${gitlab_host}/users/sign_in  2> $tmp/gitlab.sign_in.err > $tmp/gitlab.sign_in
        local GA=$(getHttpHeaderSetCookie $tmp/gitlab.sign_in.err "_ga")
        local GA_SESSION=$(getHttpHeaderSetCookie $tmp/gitlab.sign_in.err "_gitlab_session")
        local authenticity_token=$(cat $tmp/gitlab.sign_in | grep csrf-token | sed 's/.*content="\(.*\)".*/\1/g' )

        echo "authenticity_token: $authenticity_token"
        echo "_gitlab_session: $GA_SESSION"
        curl -X POST -H "Content-Type: application/x-www-form-urlencoded" --data-urlencode "_method=post" --data-urlencode "authenticity_token=$authenticity_token" -v -H "Origin: https://$gitlab_host" -H "Origin: https://$gitlab_host/users/sign_in"  -H "Cookie: $GA_SESSION" https://${gitlab_host}/users/auth/openid_connect 2> $tmp/gitlab.openid_connect.err > $tmp/gitlab.openid_connect
        local locationKathra=$(getHttpHeaderLocation $tmp/gitlab.openid_connect.err)

        echo "GA: $GA"
        echo "GA_SESSION: $GA_SESSION"
        echo "locationKathra: $locationKathra"
        curl -v -H "Cookie: $GA $GA_SESSION" ${locationKathra} 2> $tmp/gitlab.auth.kathra.err > $tmp/gitlab.auth.kathra
        local locationKC=$(getHttpHeaderLocation $tmp/gitlab.auth.kathra.err )
        local uriLogin=$(grep "action=" < $tmp/gitlab.auth.kathra | sed "s/.* action=\"\([^\"]*\)\".*/\1/" | sed 's/amp;//g' | tr -d '\r\n')
        local AUTH_SESSION_ID=$(getHttpHeaderSetCookie $tmp/gitlab.auth.kathra.err AUTH_SESSION_ID)
        local KC_RESTART=$(getHttpHeaderSetCookie $tmp/gitlab.auth.kathra.err KC_RESTART)
        local location=$(getHttpHeaderLocation $tmp/gitlab.auth.kathra.err)

        echo "uriLogin: $uriLogin"
        curl -v "$uriLogin" -H "authority: ${keycloak_host}" -H 'cache-control: max-age=0' -H "origin: https://${keycloak_host}" -H 'upgrade-insecure-requests: 1' -H 'content-type: application/x-www-form-urlencoded' -H "$UA" -H "$headerAccept" -H "referer: $location" -H 'accept-encoding: gzip, deflate, br' -H "$headerAcceptLang" -H "Cookie:$AUTH_SESSION_ID;$KC_RESTART" --data-urlencode "username=${login}"  --data-urlencode "password=${password}" --compressed 2> $tmp/gitlab.kc.post.err > $tmp/gitlab.kc.post
        local locationFinishLogin=$(getHttpHeaderLocation $tmp/gitlab.kc.post.err)

        curl -v -H "Cookie: $GA $GA_SESSION" $locationFinishLogin 2> $tmp/gitlab.finishLogin.err > $tmp/gitlab.finishLogin

        local GA_SESSION_FINAL=$(getHttpHeaderSetCookie $tmp/gitlab.finishLogin.err "_gitlab_session")
        curl https://${gitlab_host}/profile -H "Cookie: $GA $GA_SESSION_FINAL" 2> $tmp/gitlab.profile.err  > $tmp/gitlab.profile
        local AUTH_TOKEN=$(grep "name=\"authenticity_token\"" < $tmp/gitlab.profile  | sed "s/.* value=\"\([^\"]*\)\".*/\1/" | sed 's/amp;//g' | tr -d '\n')

        curl -v "https://${gitlab_host}/profile/personal_access_tokens" -H 'authority: ${gitlab_host}' -H 'cache-control: max-age=0' -H "origin: https://${gitlab_host}" -H 'upgrade-insecure-requests: 1' -H 'content-type: application/x-www-form-urlencoded' -H "${UA}" -H "${headerAccept}" -H "referer: https://${gitlab_host}/profile/personal_access_tokens" -H 'accept-encoding: gzip, deflate, br' -H "${headerAcceptLang}" -H "Cookie: $GA $GA_SESSION_FINAL" --data-urlencode "utf8=✓" \
        --data-urlencode "authenticity_token=${AUTH_TOKEN}" \
        --data-urlencode "personal_access_token[name]=kathra-token" \
        --data-urlencode "personal_access_token[expires_at]=" \
        --data-urlencode "personal_access_token[scopes][]=api" \
        --data-urlencode "personal_access_token[scopes][]=read_user" \
        --data-urlencode "personal_access_token[scopes][]=read_repository" 2> $tmp/gitlab.personal_access_tokens.err  > $tmp/gitlab.personal_access_tokens
        curl -v -H "Cookie: $GA $GA_SESSION_FINAL" "https://${gitlab_host}/profile/personal_access_tokens" 2> $tmp/gitlab.personal_access_tokens.created.err  > $tmp/gitlab.personal_access_tokens.created

        grep "id=\"created-personal-access-token\"" < $tmp/gitlab.personal_access_tokens.created  | sed "s/.* value=\"\([^\"]*\)\".*/\1/" | sed 's/amp;//g' | tr -d '\n' > $fileOut
        [ ! "$(cat $fileOut)" == "" ] && exit 0
        [ ${attempt_counter} -eq ${max_attempts} ] && echo "Unable to generated ApiToken into GitLab after ${max_attempts} attempts" && exit 1
        attempt_counter=$(($attempt_counter+1))
        echo "Unable to generated ApiToken into GitLab [ ${attempt_counter}/${max_attempts} ]"
    done
    return 0
}
export -f gitlabGenerateApiToken

gitlabGenerateApiToken "$1" "$2" "$3" "$4" "$5"