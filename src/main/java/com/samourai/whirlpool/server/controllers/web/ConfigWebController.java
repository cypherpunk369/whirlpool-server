package com.samourai.whirlpool.server.controllers.web;

import com.samourai.javaserver.web.controllers.AbstractConfigWebController;
import com.samourai.javaserver.web.models.ConfigTemplateModel;
import com.samourai.whirlpool.server.config.WhirlpoolServerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@Controller
public class ConfigWebController extends AbstractConfigWebController {
  public static final String ENDPOINT = "/status/config";

  private WhirlpoolServerConfig serverConfig;

  @Autowired
  public ConfigWebController(WhirlpoolServerConfig serverConfig) {
    this.serverConfig = serverConfig;
  }

  @RequestMapping(value = ENDPOINT, method = RequestMethod.GET)
  public String config(Model model) {
    return super.config(
        model,
        new ConfigTemplateModel(
            serverConfig.getName(), serverConfig.getName(), serverConfig.getConfigInfo()));
  }
}
