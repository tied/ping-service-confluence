package com.bstrctlmnt.job;

import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.confluence.setup.settings.SettingsManager;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import com.atlassian.scheduler.JobRunner;
import com.atlassian.scheduler.JobRunnerRequest;
import com.atlassian.scheduler.JobRunnerResponse;
import com.bstrctlmnt.service.PagesDAOService;
import com.bstrctlmnt.service.PluginDataService;
import com.bstrctlmnt.mail.PingNotification;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class PingJob implements JobRunner {

    private final PluginDataService pluginDataService;
    private final PagesDAOService pagesDAOService;

    @ComponentImport
    private final PageManager pageManager;
    @ComponentImport
    private final TransactionTemplate transactionTemplate;
    @ComponentImport
    private final SettingsManager settingsManager;


    @Autowired
    public PingJob(PageManager pageManager, TransactionTemplate transactionTemplate, SettingsManager settingsManager,
                   PluginDataService pluginDataService, PagesDAOService pagesDAOService) {
        this.pageManager = pageManager;
        this.transactionTemplate = transactionTemplate;
        this.settingsManager = settingsManager;
        this.pluginDataService = pluginDataService;
        this.pagesDAOService = pagesDAOService;
    }

    @Override
    public JobRunnerResponse runJob(JobRunnerRequest request) {
        if (request.isCancellationRequested()) {
            return JobRunnerResponse.aborted("Job cancelled.");
        }

        transactionTemplate.execute(() -> {
            //job
            long timeframe = Long.parseLong(pluginDataService.getTimeframe());
            Set<String> affectedSpaces = pluginDataService.getAffectedSpaces();
            Set<String> groups = pluginDataService.getAffectedGroups();

            if (timeframe != 0 && affectedSpaces != null && groups != null && affectedSpaces.size() > 0 && groups.size() > 0)
            {
                //get expiration date
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime requiredDate = now.minusDays(timeframe);

                //meet format in DB: "2017-03-21 09:17:10";
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss");
                Timestamp tsDate = Timestamp.valueOf(requiredDate.format(formatter));
                List<String> outdatedPagesIds = pagesDAOService.getOutdatedPages(tsDate);

                //sort pages by creator and send email
                if (outdatedPagesIds != null && outdatedPagesIds.size() > 0)
                {
                    Multimap<ConfluenceUser, Page> multiMap = ArrayListMultimap.create();
                    outdatedPagesIds.forEach((id) -> {
                        Page page = pageManager.getPage(Long.parseLong(id));
                        ConfluenceUser creator = page.getCreator();
                        if (creator != null) multiMap.put(creator, page);
                    });
                    createNotificationAndSendEmail(multiMap, timeframe);
                }
            }
            return null;
        });
        return JobRunnerResponse.success("Job finished successfully.");
    }

    private void createNotificationAndSendEmail(Multimap<ConfluenceUser, Page> multiMap, Long timeframe) {
        Set<ConfluenceUser> keys = multiMap.keySet();

        for (ConfluenceUser confluenceUser : keys)
        {
            StringBuilder links = new StringBuilder();
            Collection<Page> pages = multiMap.get(confluenceUser);

            pages.forEach((page) -> links.append("- ")
                            .append(String.format("<a href=\"%s/pages/viewpage.action?pageId=%s\">%s</a>", settingsManager.getGlobalSettings().getBaseUrl(), page.getId(), page.getDisplayTitle()))
                            .append("<br>"));

            // mail variables
            String mailbody = pluginDataService.getMailBody().replace("$creator", confluenceUser.getName())
                    .replace("$days", timeframe.toString())
                    .replace("$links", links.toString());

            new PingNotification().sendEmail(confluenceUser.getEmail(), pluginDataService.getMailSubject(), mailbody);
        }
    }
}