/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2015 ForgeRock AS. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

/*global define */

define("org/forgerock/openidm/ui/common/login/LoginView", [
    "underscore",
    "org/forgerock/commons/ui/common/LoginView",
    "org/forgerock/openidm/ui/common/util/AMLoginUtils",
    "org/forgerock/commons/ui/common/components/LoginHeader"
], function(_,
            commonLoginView,
            amLoginUtils,
            LoginHeader) {

    var LoginView = function () {},
        obj;

    LoginView.prototype = commonLoginView;

    obj = new LoginView();

    obj.render = function (args, callback) {
        var amCallback = amLoginUtils.init(this,true);

        commonLoginView.render.call(this, args, _.bind(function () {
            LoginHeader.render();

            if (callback) {
                callback();
            }

            if(amCallback) {
                amCallback();
            }

        }, this));
    };

    return obj;
});
