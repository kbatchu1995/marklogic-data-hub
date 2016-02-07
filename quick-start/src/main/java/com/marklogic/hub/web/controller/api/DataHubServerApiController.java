package com.marklogic.hub.web.controller.api;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.marklogic.hub.config.EnvironmentConfiguration;
import com.marklogic.hub.exception.DataHubException;
import com.marklogic.hub.service.DataHubService;
import com.marklogic.hub.web.controller.BaseController;
import com.marklogic.hub.web.form.LoginForm;

@RestController
@RequestMapping("/api/data-hub")
public class DataHubServerApiController extends BaseController {
    private static final Logger LOGGER = LoggerFactory.getLogger(DataHubServerApiController.class);

    @Autowired
    private EnvironmentConfiguration environmentConfiguration;
    
    @Autowired
    private DataHubService dataHubService;
    
    @RequestMapping(value="login", method = RequestMethod.POST, consumes={MediaType.APPLICATION_JSON_UTF8_VALUE}, produces={MediaType.APPLICATION_JSON_UTF8_VALUE})
    public LoginForm postLogin(@RequestBody LoginForm loginForm, BindingResult bindingResult, HttpSession session, HttpServletRequest request) throws Exception {
        try {
            updateEnvironmentConfiguration(loginForm);
            
            loginForm.setInstalled(dataHubService.isInstalled());
            loginForm.setServerVersionAccepted(dataHubService.isServerAcceptable());
            loginForm.setHasErrors(false);
            loginForm.setLoggedIn(true);
            
            environmentConfiguration.saveConfigurationToFile();
            
            session.setAttribute("loginForm", loginForm);
        }
        catch (DataHubException e) {
            LOGGER.error("Login failed", e);
            loginForm.setLoggedIn(false);
            displayError(loginForm, null, null, e.getMessage());
        }
        
        return loginForm;
    }
    
    @RequestMapping(value="login", method = RequestMethod.GET)
    public LoginForm getLoginStatus(HttpSession session) {
    	LoginForm loginForm = (LoginForm) session.getAttribute("loginForm");
    	if(loginForm == null) {
    		loginForm = new LoginForm();
    		this.environmentConfiguration.loadConfigurationFromFile();
    		this.retrieveEnvironmentConfiguration(loginForm);
    		session.setAttribute("loginForm", loginForm);
    	}
        return loginForm;
    }
    
    @RequestMapping(value="logout", method = RequestMethod.POST)
    public LoginForm postLogout(HttpSession session) {
    	LoginForm loginForm = (LoginForm) session.getAttribute("loginForm");
    	loginForm.setLoggedIn(false);
    	this.environmentConfiguration.removeSavedConfiguration();
    	this.retrieveEnvironmentConfiguration(loginForm);
    	return loginForm;
    }
    
    @RequestMapping(value="install", method = RequestMethod.POST)
    public void install() {
        dataHubService.install();
    }
    
    @RequestMapping(value="uninstall", method = RequestMethod.POST)
    public void uninstall() {
        dataHubService.uninstall();
    }
    
    @RequestMapping(value="install-user-modules", method = RequestMethod.POST)
    public void installUserModules() {
        dataHubService.installUserModules();
    }
    
    private void updateEnvironmentConfiguration(LoginForm loginForm) {
        environmentConfiguration.setMLHost(loginForm.getMlHost());
        environmentConfiguration.setMLRestPort(loginForm.getMlRestPort());
        environmentConfiguration.setMLUsername(loginForm.getMlUsername());
        environmentConfiguration.setMLPassword(loginForm.getMlPassword());
    }
    
    private void retrieveEnvironmentConfiguration(LoginForm loginForm) {
    	loginForm.setMlHost(environmentConfiguration.getMLHost());
    	loginForm.setMlRestPort(environmentConfiguration.getMLRestPort());
    	loginForm.setMlUsername(environmentConfiguration.getMLUsername());
    	loginForm.setMlPassword(environmentConfiguration.getMLPassword());
    }
}
